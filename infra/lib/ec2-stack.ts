import { CfnOutput, Duration, Stack, StackProps } from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as route53 from 'aws-cdk-lib/aws-route53';
import { Construct } from 'constructs';

export interface Ec2StackProps extends StackProps {
  /** ECR image tag to deploy (git SHA or "latest"). */
  readonly imageTag: string;
  /** ECR repository name, e.g. "ambonmud/app". */
  readonly ecrRepoName: string;
  /**
   * Optional apex domain for Route 53 DNS.
   * If provided, creates an A record: play.<domain> → Elastic IP.
   * Requires a Route 53 hosted zone for <domain> in the same account.
   */
  readonly domain?: string;
  /**
   * Optional fully-qualified hostname for nginx TLS termination, e.g. "mud.ambon.dev".
   * If provided, nginx + certbot are installed and a `setup-tls` helper script is
   * written to /usr/local/bin/setup-tls. Run it once (via SSM) after DNS is live:
   *   setup-tls
   * Ports 80 and 443 are always opened in the security group for HTTP → HTTPS redirects.
   */
  readonly hostname?: string;
  /**
   * Optional URL to a remote application-local.yaml config overlay (e.g. R2/S3).
   * If provided, the systemd service curls it to /app/data/application-local.yaml
   * on every (re)start. The entrypoint places /app/data on the classpath before
   * the fat JAR, so this overlay augments the bundled application.yaml.
   *
   * Used by the Auringold lore repo to publish hot-reloadable config without
   * baking it into the container image.
   */
  readonly loreConfigUrl?: string;
  /**
   * Optional base URL for world zone YAML files (e.g. "https://auringold.ambon.dev/world").
   * If provided (along with loreConfigUrl), the systemd service parses zone filenames
   * from the lore config's ambonmud.world.resources list and curls each one from
   * ${worldZonesBaseUrl}/<filename>. The entrypoint places /app/data on the classpath
   * before the fat JAR, so these zones shadow the bundled placeholder zones.
   *
   * Per-file fetching (rather than a tarball) lets us use object stores like R2
   * that don't support directory listing.
   */
  readonly worldZonesBaseUrl?: string;
  /**
   * Optional URL to the Arcanum-generated sprites.yaml (e.g.
   * "https://auringold.ambon.dev/sprites.yaml"). When set, the systemd service
   * curls it to /app/data/sprites.yaml on every (re)start, and SpriteLoader
   * picks it up via the AMBONMUD_DATA_DIR filesystem fallback. This is a
   * separate file from the bundled world/sprites.yaml stub — the Arcanum file
   * contains the real tier/class/staff sprite definitions that are too big
   * (and too often-regenerated) to bake into the JAR.
   */
  readonly spritesUrl?: string;
  /**
   * Optional URL to the lore-repo achievements.yaml (e.g.
   * "https://auringold.ambon.dev/world/achievements.yaml"). When set, the
   * systemd service curls it to /app/data/world/achievements.yaml AFTER
   * fetch-world-zones runs (which `rm`s everything under /app/data/world/
   * before repopulating), so the order matters — the achievements curl must
   * come after the zone fetch or it gets wiped. AchievementLoader picks it
   * up via the AMBONMUD_DATA_DIR filesystem fallback and it shadows the
   * bundled JAR copy.
   */
  readonly achievementsUrl?: string;
  /**
   * Optional SSM Parameter Store parameter name (e.g. "/ambonmud/demo/admin-token")
   * that holds the admin API token as a SecureString. When set:
   *   - The instance role is granted ssm:GetParameter on this specific parameter
   *   - An ExecStartPre fetches it at every systemd start and writes
   *     /etc/ambonmud/secrets.env with AMBONMUD_ADMIN_TOKEN=<value>
   *   - generate-htpasswd reads the token from that env file instead of
   *     grepping application-local.yaml
   *   - docker run passes --env-file /etc/ambonmud/secrets.env to the container
   *     so Hoplite picks up the env var and overrides any placeholder in the
   *     public lore config overlay
   *
   * Created once out-of-band via:
   *   aws ssm put-parameter --name /ambonmud/demo/admin-token \
   *     --type SecureString --value "$(openssl rand -hex 32)" --region us-east-1
   *
   * Rotated by re-running put-parameter with --overwrite and then restarting
   * ambonmud.service (no CDK redeploy needed).
   */
  readonly adminTokenSsmParameterName?: string;
  /**
   * Optional JVM flags passed to the container as JAVA_OPTS.
   *
   * Example: "-Xms2g -Xmx2g -XX:+UseZGC -XX:+ZGenerational"
   *
   * IMPORTANT: these flags must fit within the instance's physical RAM. The
   * default t4g.micro has only 1 GB of RAM and also runs Prometheus + Grafana
   * containers on the same host, so a 2 GB heap requires bumping the instance
   * to at least t4g.medium (4 GB) — set it directly in this stack's Instance
   * construct. Setting a too-large heap on an undersized box leads to swap
   * death or OOM-kill.
   */
  readonly javaOpts?: string;
}

/**
 * Minimal single-instance EC2 deployment (~$4-5/mo).
 *
 * Provisions:
 *   - VPC with a single public subnet (no NAT gateway)
 *   - Security group: TCP 4000 (telnet) + 80/443 (HTTP/HTTPS) + 8080 (web) + 9091 (admin) + 3000 (Grafana)
 *   - IAM role: ECR pull + SSM Session Manager (no SSH key needed)
 *   - t4g.nano (ARM64) running Amazon Linux 2023
 *   - Docker + systemd service that pulls and runs the AmbonMUD container
 *   - Elastic IP (persists across stop/start; no charge while attached)
 *   - Optional Route 53 A record (play.<domain> → EIP)
 *   - Optional nginx TLS termination (hostname prop): installs nginx + certbot,
 *     writes nginx config, installs `setup-tls` helper
 *
 * YAML persistence is used (no RDS or Redis).
 * Player data lives at /app/data on the root EBS volume.
 *
 * To deploy a new image tag without replacing the instance (and losing
 * player YAML data), run the update helper via SSM:
 *   aws ssm start-session --target <instanceId>
 *   $ update-ambonmud <new-tag>
 *
 * To set up TLS after DNS is live:
 *   aws ssm start-session --target <instanceId>
 *   $ setup-tls
 */
export class Ec2Stack extends Stack {
  constructor(scope: Construct, id: string, props: Ec2StackProps) {
    super(scope, id, props);

    const { imageTag, ecrRepoName, domain, hostname, loreConfigUrl, worldZonesBaseUrl, spritesUrl, achievementsUrl, adminTokenSsmParameterName, javaOpts } = props;
    const ecrUri = `${this.account}.dkr.ecr.${this.region}.amazonaws.com/${ecrRepoName}`;

    // -------------------------------------------------------------------------
    // VPC: one public subnet, no NAT gateway.
    // The instance gets a public IP directly — saves the ~$33/mo NAT gateway.
    // -------------------------------------------------------------------------
    const vpc = new ec2.Vpc(this, 'Vpc', {
      maxAzs: 1,
      natGateways: 0,
      subnetConfiguration: [
        {
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 28,
        },
      ],
    });

    // -------------------------------------------------------------------------
    // Security group: telnet + web from anywhere.
    // No SSH inbound — use SSM Session Manager (no key pair required).
    // -------------------------------------------------------------------------
    const sg = new ec2.SecurityGroup(this, 'Sg', {
      vpc,
      description: 'AmbonMUD EC2',
      allowAllOutbound: true,
    });
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(4000), 'Telnet');
    sg.addIngressRule(ec2.Peer.anyIpv6(), ec2.Port.tcp(4000), 'Telnet IPv6');
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(80), 'HTTP');
    sg.addIngressRule(ec2.Peer.anyIpv6(), ec2.Port.tcp(80), 'HTTP IPv6');
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(443), 'HTTPS');
    sg.addIngressRule(ec2.Peer.anyIpv6(), ec2.Port.tcp(443), 'HTTPS IPv6');
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(8080), 'Web direct (bypass nginx)');
    sg.addIngressRule(ec2.Peer.anyIpv6(), ec2.Port.tcp(8080), 'Web direct IPv6');
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(9091), 'Admin API');
    sg.addIngressRule(ec2.Peer.anyIpv6(), ec2.Port.tcp(9091), 'Admin API IPv6');
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(3000), 'Grafana');
    sg.addIngressRule(ec2.Peer.anyIpv6(), ec2.Port.tcp(3000), 'Grafana IPv6');

    // -------------------------------------------------------------------------
    // IAM role: ECR pull + SSM for browser-based shell access.
    // -------------------------------------------------------------------------
    const role = new iam.Role(this, 'InstanceRole', {
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonEC2ContainerRegistryReadOnly'),
        iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore'),
      ],
    });

    // Grant read access to the admin-token SSM parameter so the systemd
    // fetch-admin-token ExecStartPre can populate /etc/ambonmud/secrets.env.
    // Scoped to the exact parameter name — AmazonSSMManagedInstanceCore (above)
    // deliberately does NOT include GetParameter for arbitrary parameters.
    if (adminTokenSsmParameterName) {
      role.addToPolicy(
        new iam.PolicyStatement({
          actions: ['ssm:GetParameter'],
          resources: [
            `arn:aws:ssm:${this.region}:${this.account}:parameter${adminTokenSsmParameterName}`,
          ],
        }),
      );
    }

    // -------------------------------------------------------------------------
    // User data: install Docker, write systemd service, install update helper.
    //
    // userDataCausesReplacement is left at its default (false) intentionally:
    // CDK changes to user data will NOT replace the instance and won't
    // clobber player YAML data on disk. To roll out a new image tag, run
    // `update-ambonmud <tag>` via SSM Session Manager.
    // -------------------------------------------------------------------------
    const userData = ec2.UserData.forLinux();
    // When hostname is set, the nginx+certbot stack is needed BEFORE the
    // first systemctl start ambonmud — because generate-htpasswd (which runs
    // as an ExecStartPre on ambonmud.service) tries to write /etc/nginx/.htpasswd,
    // and /etc/nginx/ doesn't exist until the nginx package is installed. The
    // install used to live in the `if (hostname)` block lower in this file,
    // but that block runs AFTER systemctl start, so it's too late. Installing
    // these packages in the same unconditional dnf invocation as docker is
    // the simplest ordering fix; the nginx *config* files (nginx.conf,
    // setup-tls helper, etc.) stay in the `if (hostname)` block where they
    // belong.
    //
    // httpd-tools is here unconditionally because generate-htpasswd runs
    // unconditionally and calls htpasswd regardless — keeping the dev/local
    // path working without nginx still needs it to exist. See the 2026-04-07
    // incident for the full chain of failures if you're tempted to simplify.
    const nginxPackages = hostname ? ' nginx certbot python3-certbot-nginx bind-utils' : '';
    userData.addCommands(
      'set -euo pipefail',
      `dnf install -y docker amazon-ssm-agent emacs-nox httpd-tools${nginxPackages}`,
      'systemctl enable --now docker',
      'systemctl enable --now amazon-ssm-agent',
      // UID 1001 matches the pinned ambonmud user inside the container (Dockerfile).
      'mkdir -p /app/data && chown 1001:1001 /app/data',
      '',
      // ---- Swap file: 512 MB safety net against OOM on t4g.micro ---------------
      'dd if=/dev/zero of=/swapfile bs=1M count=512',
      'chmod 600 /swapfile',
      'mkswap /swapfile',
      'swapon /swapfile',
      "echo '/swapfile swap swap defaults 0 0' >> /etc/fstab",
      '',
      // ---- Docker network for inter-container communication -------------------
      'docker network create ambonmud-net || true',
      '',
      // ---- Prometheus config --------------------------------------------------
      'mkdir -p /app/prometheus',
      `cat > /app/prometheus/prometheus.yml << 'PROM_END'`,
      'global:',
      '  scrape_interval: 15s',
      '',
      'rule_files:',
      '  - /etc/prometheus/prometheus-alerts.yml',
      '',
      'scrape_configs:',
      '  - job_name: ambonmud',
      '    static_configs:',
      '      - targets: [ambonmud:8080]',
      '    metrics_path: /metrics',
      'PROM_END',
      '',
      `cat > /app/prometheus/prometheus-alerts.yml << 'ALERTS_END'`,
      'groups:',
      '  - name: ambonmud-core-alerts',
      '    rules:',
      '      - alert: AmbonEngineTickOverrunHigh',
      '        expr: rate(engine_tick_overrun_total[5m]) > 0.1',
      '        for: 10m',
      '        labels:',
      '          severity: warning',
      '        annotations:',
      '          summary: Engine tick overruns are sustained',
      '      - alert: AmbonPlayerSaveFailures',
      '        expr: increase(player_save_failures_total[10m]) > 0',
      '        for: 0m',
      '        labels:',
      '          severity: critical',
      '        annotations:',
      '          summary: Player save failures detected',
      '      - alert: AmbonMetricsEndpointDown',
      '        expr: up{job="ambonmud"} == 0',
      '        for: 2m',
      '        labels:',
      '          severity: critical',
      '        annotations:',
      '          summary: AmbonMUD metrics endpoint is down',
      'ALERTS_END',
      '',
      // ---- Grafana provisioning -----------------------------------------------
      'mkdir -p /app/grafana/provisioning/datasources /app/grafana/provisioning/dashboards',
      '',
      `cat > /app/grafana/provisioning/datasources/prometheus.yml << 'DS_END'`,
      'apiVersion: 1',
      'datasources:',
      '  - name: Prometheus',
      '    type: prometheus',
      '    access: proxy',
      '    url: http://prometheus:9090/prometheus',
      '    isDefault: true',
      '    editable: false',
      'DS_END',
      '',
      `cat > /app/grafana/provisioning/dashboards/dashboard.yml << 'DASH_END'`,
      'apiVersion: 1',
      'providers:',
      '  - name: AmbonMUD',
      '    orgId: 1',
      '    type: file',
      '    disableDeletion: false',
      '    updateIntervalSeconds: 30',
      '    options:',
      '      path: /etc/grafana/provisioning/dashboards',
      'DASH_END',
      '',
      // Grafana dashboards are fetched from the repo at first boot.
      // The update-ambonmud helper refreshes them on each deploy.
      `DASH_BASE="https://raw.githubusercontent.com/jnoecker/AmbonMUD/main/infra/grafana/provisioning/dashboards"`,
      'for dash in ambon_overview ambon_engine ambon_engine_health ambon_gameplay ambon_jvm ambon_persistence ambon_scheduler ambon_transport; do',
      '  curl -fsSL -o "/app/grafana/provisioning/dashboards/${dash}.json" "${DASH_BASE}/${dash}.json" || echo "Warning: failed to fetch ${dash}.json"',
      'done',
      '',
      // ---- Prometheus systemd service -----------------------------------------
      `cat > /etc/systemd/system/prometheus.service << 'PROM_SVC_END'`,
      '[Unit]',
      'Description=Prometheus',
      'After=docker.service',
      'Requires=docker.service',
      '',
      '[Service]',
      'Restart=always',
      'RestartSec=10',
      'ExecStartPre=-/usr/bin/docker rm -f prometheus',
      'ExecStart=/usr/bin/docker run --name prometheus \\',
      '  --network ambonmud-net \\',
      '  -p 9090:9090 \\',
      '  -v /app/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro \\',
      '  -v /app/prometheus/prometheus-alerts.yml:/etc/prometheus/prometheus-alerts.yml:ro \\',
      '  prom/prometheus:v2.51.2 \\',
      '  --config.file=/etc/prometheus/prometheus.yml \\',
      '  --web.external-url=/prometheus/',
      'ExecStop=/usr/bin/docker stop prometheus',
      '',
      '[Install]',
      'WantedBy=multi-user.target',
      'PROM_SVC_END',
      '',
      // ---- Grafana systemd service --------------------------------------------
      `cat > /etc/systemd/system/grafana.service << 'GRAF_SVC_END'`,
      '[Unit]',
      'Description=Grafana',
      'After=docker.service prometheus.service',
      'Requires=docker.service',
      '',
      '[Service]',
      'Restart=always',
      'RestartSec=10',
      'ExecStartPre=-/usr/bin/docker rm -f grafana',
      'ExecStart=/usr/bin/docker run --name grafana \\',
      '  --network ambonmud-net \\',
      '  -p 3000:3000 \\',
      '  -e GF_SECURITY_ADMIN_PASSWORD=admin \\',
      `  -e GF_SERVER_ROOT_URL=https://${hostname || 'localhost'}/grafana/ \\`,
      '  -e GF_SERVER_SERVE_FROM_SUB_PATH=true \\',
      '  -e GF_AUTH_ANONYMOUS_ENABLED=true \\',
      '  -e GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer \\',
      '  -v /app/grafana/provisioning:/etc/grafana/provisioning:ro \\',
      '  grafana/grafana:10.4.2',
      'ExecStop=/usr/bin/docker stop grafana',
      '',
      '[Install]',
      'WantedBy=multi-user.target',
      'GRAF_SVC_END',
      '',
      // ---- update-ambonmud helper script ------------------------------------
      // Pulls a new image tag, patches the service file, and restarts.
      // Usage (via SSM shell): update-ambonmud <tag>
      // ----------------------------------------------------------------------
      `cat > /usr/local/bin/update-ambonmud << 'SCRIPT_END'`,
      '#!/bin/bash',
      'set -euo pipefail',
      `NEW_TAG="\${1:-${imageTag}}"`,
      `aws ecr get-login-password --region ${this.region} | docker login --username AWS --password-stdin ${ecrUri}`,
      `docker pull "${ecrUri}:\${NEW_TAG}"`,
      `sed -i "s|${ecrUri}:.*|${ecrUri}:\${NEW_TAG}|g" /etc/systemd/system/ambonmud.service`,
      'systemctl daemon-reload',
      'systemctl restart ambonmud',
      `echo "AmbonMUD updated to \${NEW_TAG}"`,
      'SCRIPT_END',
      'chmod +x /usr/local/bin/update-ambonmud',
      '',
      // ---- fetch-world-zones helper script -----------------------------------
      // Reads zone filenames from application-local.yaml's ambonmud.world.resources
      // list and curls each from the configured base URL. This per-file approach
      // works with object stores like R2 that don't support directory listing.
      // Called by ExecStartPre on every service (re)start, and by the
      // refresh-demo-world workflow when content changes between deploys.
      // ----------------------------------------------------------------------
      ...(worldZonesBaseUrl
        ? [
            `cat > /usr/local/bin/fetch-world-zones << 'SCRIPT_END'`,
            '#!/bin/bash',
            'set -euo pipefail',
            'CONFIG=/app/data/application-local.yaml',
            'if [ ! -f "$CONFIG" ]; then',
            '  echo "No config overlay found at $CONFIG — skipping zone fetch"',
            '  exit 0',
            'fi',
            'mkdir -p /app/data/world',
            'rm -f /app/data/world/*.yaml',
            '# Strip any trailing slash so we don\'t produce "//<filename>" URLs.',
            '# R2 treats "/world//pixel_haven.yaml" as a different key from',
            '# "/world/pixel_haven.yaml" and returns 404 — the script previously',
            '# silently served stale 404s when WORLD_ZONES_BASE_URL was set with',
            '# a trailing slash in the GitHub secret.',
            `BASE_URL="${worldZonesBaseUrl}"`,
            'BASE_URL="${BASE_URL%/}"',
            '# Parse "- world/<name>.yaml" entries from the resources list',
            'grep -E "^\\s*-\\s*world/" "$CONFIG" | sed "s/.*- *//" | while read -r entry; do',
            '  filename="${entry#world/}"',
            '  echo "Fetching zone: $filename"',
            '  # --retry-all-errors is required for 404 retries: plain --retry only',
            '  # covers 5xx + connection errors. Cloudflare R2 edge POPs cache 404',
            '  # responses independently and can serve stale 404s for several minutes',
            '  # after an upload, so we spin in-place rather than failing the unit.',
            '  # -A overrides the default "curl/X.Y.Z" user-agent because Cloudflare',
            '  # bot management was serving JS challenges (HTTP 403, cf-mitigated:',
            '  # challenge) to default-curl requests from this EC2 IP, breaking the',
            '  # zone fetch on every restart. Mozilla-prefixed UAs bypass the rule.',
            '  curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors \\',
            '    -A "Mozilla/5.0 (compatible; AmbonMUD-fetch/1.0)" \\',
            '    -o "/app/data/world/$filename" "$BASE_URL/$filename"',
            'done',
            'echo "World zones fetched to /app/data/world/"',
            'SCRIPT_END',
            'chmod +x /usr/local/bin/fetch-world-zones',
            '',
          ]
        : []),
      // ---- fetch-admin-token helper script ------------------------------------
      // Reads the admin token from AWS SSM Parameter Store and materializes it
      // into two files the rest of the boot sequence reads:
      //
      //   1. /etc/ambonmud/secrets.env — AMBONMUD_ADMIN_TOKEN=<value>, mode 600,
      //      root-only. Used by docker --env-file for container env, and by
      //      generate-htpasswd to build the nginx basic-auth htpasswd.
      //
      //   2. /app/data/secrets.yaml — Hoplite secrets overlay. Highest-priority
      //      config source (see AppConfigLoader.kt) so the app's loaded
      //      admin.token matches the real SSM value regardless of what the
      //      creator-generated application-local.yaml carries as a placeholder.
      //      Without this, a creator overlay with admin.token: "" (or any
      //      non-matching placeholder) causes the app to reject the real token
      //      at the admin API auth layer, while nginx basic-auth (built from
      //      secrets.env) accepts it — the two layers disagree and no single
      //      password works. The secrets overlay makes both layers agree.
      //
      // Only installed when adminTokenSsmParameterName is set.
      // ----------------------------------------------------------------------
      ...(adminTokenSsmParameterName
        ? [
            `cat > /usr/local/bin/fetch-admin-token << 'FETCH_END'`,
            '#!/bin/bash',
            'set -euo pipefail',
            `PARAM_NAME="${adminTokenSsmParameterName}"`,
            `REGION="${this.region}"`,
            'mkdir -p /etc/ambonmud /app/data',
            'umask 077',
            'token=$(aws ssm get-parameter --name "$PARAM_NAME" --with-decryption --query Parameter.Value --output text --region "$REGION")',
            'if [ -z "$token" ]; then',
            '  echo "Empty admin token from SSM — refusing to start" >&2',
            '  exit 1',
            'fi',
            '# secrets.env — consumed by docker --env-file and generate-htpasswd.',
            'printf "AMBONMUD_ADMIN_TOKEN=%s\\n" "$token" > /etc/ambonmud/secrets.env',
            'chmod 600 /etc/ambonmud/secrets.env',
            '# secrets.yaml — Hoplite overlay read by the container (UID 1001).',
            '# Single-quoted YAML scalar: safe for SSM hex tokens; if token style',
            '# ever changes to include single-quotes or backslashes, switch to',
            '# python -c "import yaml,sys; yaml.safe_dump(...)" for robustness.',
            "printf \"ambonmud:\\n  admin:\\n    token: '%s'\\n\" \"$token\" > /app/data/secrets.yaml",
            'chown 1001:1001 /app/data/secrets.yaml',
            'chmod 600 /app/data/secrets.yaml',
            'echo "Admin token materialized to secrets.env + secrets.yaml"',
            'FETCH_END',
            'chmod +x /usr/local/bin/fetch-admin-token',
            '',
          ]
        : []),
      // ---- generate-htpasswd helper script ------------------------------------
      // Writes an htpasswd file for nginx basic auth on /grafana/, /prometheus/,
      // and /admin/. The admin token is sourced from (in order of preference):
      //   1. /etc/ambonmud/secrets.env (AMBONMUD_ADMIN_TOKEN=<value>), populated
      //      by the fetch-admin-token ExecStartPre from SSM Parameter Store when
      //      adminTokenSsmParameterName is set. This is the preferred path —
      //      it keeps the token out of the publicly-fetched lore config overlay.
      //   2. application-local.yaml `token:` field, grep'd out of the fetched
      //      config overlay. This is the legacy path, kept only so dev/local
      //      setups that predate the SSM migration still work.
      // Called by ExecStartPre after fetch-admin-token (if enabled) and after
      // the lore config is fetched.
      // ----------------------------------------------------------------------
      `cat > /usr/local/bin/generate-htpasswd << 'SCRIPT_END'`,
      '#!/bin/bash',
      'set -euo pipefail',
      '# Defense in depth: soft-fail (exit 0) if either htpasswd or /etc/nginx',
      '# is missing, rather than wedging the whole ambonmud.service in a',
      '# restart loop. nginx basic auth for /grafana/, /prometheus/, /admin/',
      '# will be broken but the MUD itself still boots. When hostname is set,',
      '# both should be present because the nginx package is installed in the',
      '# top-level dnf call before systemctl start ambonmud runs.',
      'HTPASSWD=/etc/nginx/.htpasswd',
      'ENV_FILE=/etc/ambonmud/secrets.env',
      'if ! command -v htpasswd >/dev/null 2>&1; then',
      '  echo "htpasswd not installed (httpd-tools missing) — skipping htpasswd generation"',
      '  exit 0',
      'fi',
      'if [ ! -d "$(dirname "$HTPASSWD")" ]; then',
      '  echo "$(dirname "$HTPASSWD") does not exist (nginx not installed) — skipping htpasswd generation"',
      '  exit 0',
      'fi',
      'if [ -f "$ENV_FILE" ]; then',
      '  # shellcheck source=/dev/null',
      '  . "$ENV_FILE"',
      '  TOKEN="${AMBONMUD_ADMIN_TOKEN:-}"',
      'else',
      '  CONFIG=/app/data/application-local.yaml',
      '  if [ ! -f "$CONFIG" ]; then',
      '    echo "No secrets env file and no config overlay — skipping htpasswd generation"',
      '    exit 0',
      '  fi',
      '  # Legacy: strip any surrounding double- or single-quotes around the value.',
      `  TOKEN=$(grep -E "^\\s*token:" "$CONFIG" | head -1 | sed "s/.*token:\\s*//" | tr -d "\\"'")`,
      'fi',
      'if [ -z "$TOKEN" ]; then',
      '  echo "No admin token found — skipping htpasswd generation"',
      '  exit 0',
      'fi',
      'htpasswd -bc "$HTPASSWD" admin "$TOKEN"',
      'echo "htpasswd written to $HTPASSWD"',
      'SCRIPT_END',
      'chmod +x /usr/local/bin/generate-htpasswd',
      '',
      // ---- systemd service --------------------------------------------------
      `cat > /etc/systemd/system/ambonmud.service << 'SERVICE_END'`,
      '[Unit]',
      'Description=AmbonMUD',
      'After=docker.service network-online.target',
      'Requires=docker.service',
      '',
      '[Service]',
      'Restart=always',
      'RestartSec=10',
      `ExecStartPre=/bin/bash -c 'aws ecr get-login-password --region ${this.region} | docker login --username AWS --password-stdin ${ecrUri}'`,
      `ExecStartPre=/usr/bin/docker pull ${ecrUri}:${imageTag}`,
      'ExecStartPre=-/usr/bin/docker rm -f ambonmud',
      // Fetch the lore config overlay (application-local.yaml) from the Auringold
      // R2 bucket. Must run before generate-htpasswd (which reads admin token
      // from the overlay) and before fetch-world-zones (which reads the
      // ambonmud.world.resources list from the overlay).
      // --retry-all-errors lets curl retry 404s, which Cloudflare R2 edge POPs
      // can cache for several minutes after a fresh upload.
      // -A overrides the default "curl/X.Y.Z" user-agent — see fetch-world-zones
      // comment above for the Cloudflare bot-challenge incident that motivated this.
      ...(loreConfigUrl
        ? [
            `ExecStartPre=/usr/bin/curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors -A "Mozilla/5.0 (compatible; AmbonMUD-fetch/1.0)" -o /app/data/application-local.yaml ${loreConfigUrl}`,
          ]
        : []),
      // Fetch world zone YAML files listed in the lore config's ambonmud.world.resources.
      // The helper script parses filenames from the config overlay, then curls each
      // from the base URL. The entrypoint places /app/data on the classpath before
      // the fat JAR, so these zones shadow the bundled placeholder zones.
      ...(worldZonesBaseUrl
        ? [`ExecStartPre=/usr/local/bin/fetch-world-zones`]
        : []),
      // Fetch the Arcanum-generated sprites.yaml from R2 to /app/data/sprites.yaml.
      // SpriteLoader picks it up via the AMBONMUD_DATA_DIR filesystem fallback.
      // Not fetched by fetch-world-zones because sprites.yaml lives at the R2
      // root, not under /world/, and the sprite loader calls loadFromResource
      // with "sprites.yaml" (no "world/" prefix) so the target path is
      // /app/data/sprites.yaml, not /app/data/world/sprites.yaml.
      ...(spritesUrl
        ? [
            `ExecStartPre=/usr/bin/curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors -A "Mozilla/5.0 (compatible; AmbonMUD-fetch/1.0)" -o /app/data/sprites.yaml ${spritesUrl}`,
          ]
        : []),
      // Fetch the lore-repo achievements.yaml to /app/data/world/achievements.yaml.
      // Must run AFTER fetch-world-zones because that script does
      // `rm -f /app/data/world/*.yaml` before repopulating, which would
      // otherwise wipe the file. AchievementLoader picks it up via the
      // AMBONMUD_DATA_DIR filesystem fallback and it shadows the bundled
      // JAR copy.
      ...(achievementsUrl
        ? [
            `ExecStartPre=/usr/bin/curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors -A "Mozilla/5.0 (compatible; AmbonMUD-fetch/1.0)" -o /app/data/world/achievements.yaml ${achievementsUrl}`,
          ]
        : []),
      // Fetch the admin API token from SSM Parameter Store. Runs before
      // generate-htpasswd (which reads secrets.env) and before the container
      // starts (which bind-mounts /app/data holding secrets.yaml). Fails the
      // unit if the parameter is missing or unreadable — we'd rather refuse
      // to start than launch with a broken/missing admin token.
      ...(adminTokenSsmParameterName
        ? [`ExecStartPre=/usr/local/bin/fetch-admin-token`]
        : []),
      // Generate htpasswd for nginx basic auth on /grafana/, /prometheus/, /admin/.
      'ExecStartPre=/usr/local/bin/generate-htpasswd',
      // AMBONMUD_DATA_DIR tells AppConfigLoader + WorldLoader to look in
      // /app/data for the application-local.yaml overlay and any externally-
      // fetched zone YAMLs (populated by fetch-world-zones above). Without
      // this, the JVM only sees the bundled classpath resources and silently
      // ignores everything the fetch scripts wrote to disk.
      // --env-file (when adminTokenSsmParameterName is set) pushes
      // AMBONMUD_ADMIN_TOKEN from /etc/ambonmud/secrets.env into the
      // container, where Hoplite picks it up and overrides ambonmud.admin.token.
      `ExecStart=/usr/bin/docker run --name ambonmud --network ambonmud-net -p 4000:4000 -p 8080:8080 -p 9091:9091 -v /app/data:/app/data ${adminTokenSsmParameterName ? '--env-file /etc/ambonmud/secrets.env ' : ''}-e AMBONMUD_DATA_DIR=/app/data -e AMBONMUD_PERSISTENCE_BACKEND=YAML -e AMBONMUD_REDIS_ENABLED=false${javaOpts ? ` -e JAVA_OPTS=${JSON.stringify(javaOpts)}` : ''} ${ecrUri}:${imageTag}`,
      'ExecStop=/usr/bin/docker stop ambonmud',
      '',
      '[Install]',
      'WantedBy=multi-user.target',
      'SERVICE_END',
      '',
      'systemctl daemon-reload',
      'systemctl enable ambonmud prometheus grafana',
    );

    // -------------------------------------------------------------------------
    // Optional nginx + TLS setup (when hostname is provided).
    //
    // Writes the nginx reverse-proxy config and the `setup-tls` helper that
    // provisions a Let's Encrypt cert via HTTP-01 once DNS is live.
    //
    // Ordering note: this block runs BEFORE `systemctl start ambonmud` on
    // purpose. If ambonmud's ExecStartPre chain takes a long time (e.g. the
    // fetch-world-zones retry loop when R2 is slow or a URL is misconfigured)
    // the `systemctl start ambonmud` call can exceed cloud-init's default
    // scripts-user timeout, which aborts the rest of userData. When that
    // happened before this reorder, the nginx config and setup-tls helper
    // were never written and the operator had to recreate them by hand.
    // Writing these files first guarantees that even a wedged first boot
    // leaves the instance configurable.
    // -------------------------------------------------------------------------
    if (hostname) {
      userData.addCommands(
        '',
        // nginx/certbot/httpd-tools/bind-utils are installed earlier in the
        // top-level dnf call (see `nginxPackages` above) because they need
        // to exist before the first systemctl start ambonmud. This block
        // now only writes config files and wires up systemd.
        'systemctl enable nginx',
        '',
        // nginx reverse-proxy config: HTTP + WebSocket → localhost:8080
        `cat > /etc/nginx/conf.d/ambonmud.conf << 'NGINX_END'`,
        '# WebSocket upgrade helper',
        'map $http_upgrade $connection_upgrade {',
        '    default upgrade;',
        "    ''      close;",
        '}',
        '',
        'server {',
        '    listen 80;',
        `    server_name ${hostname};`,
        '',
        '    location / {',
        '        proxy_pass http://localhost:8080;',
        '        proxy_http_version 1.1;',
        '        proxy_set_header Upgrade $http_upgrade;',
        '        proxy_set_header Connection $connection_upgrade;',
        '        proxy_set_header Host $host;',
        '        proxy_set_header X-Real-IP $remote_addr;',
        '        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;',
        '        proxy_set_header X-Forwarded-Proto $scheme;',
        '        # Keep WebSocket connections alive',
        '        proxy_read_timeout 3600;',
        '    }',
        '',
        '    location /grafana/ {',
        '        auth_basic "AmbonMUD Admin";',
        '        auth_basic_user_file /etc/nginx/.htpasswd;',
        '        proxy_pass http://localhost:3000/grafana/;',
        '        proxy_set_header Host $host;',
        '        proxy_set_header X-Real-IP $remote_addr;',
        '        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;',
        '        proxy_set_header X-Forwarded-Proto $scheme;',
        '    }',
        '',
        '    location /prometheus/ {',
        '        auth_basic "AmbonMUD Admin";',
        '        auth_basic_user_file /etc/nginx/.htpasswd;',
        '        proxy_pass http://localhost:9090/prometheus/;',
        '        proxy_set_header Host $host;',
        '        proxy_set_header X-Real-IP $remote_addr;',
        '        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;',
        '        proxy_set_header X-Forwarded-Proto $scheme;',
        '    }',
        '',
        '    location /admin/ {',
        '        auth_basic "AmbonMUD Admin";',
        '        auth_basic_user_file /etc/nginx/.htpasswd;',
        '        proxy_pass http://localhost:9091/;',
        '        proxy_set_header Host $host;',
        '        proxy_set_header X-Real-IP $remote_addr;',
        '        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;',
        '        proxy_set_header X-Forwarded-Proto $scheme;',
        '    }',
        '}',
        'NGINX_END',
        '',
        // setup-tls: provision Let's Encrypt cert, can be run manually or via systemd
        `cat > /usr/local/bin/setup-tls << 'SETUP_END'`,
        '#!/bin/bash',
        'set -euo pipefail',
        `DOMAIN="\${1:-${hostname}}"`,
        'TOKEN=$(curl -s --max-time 2 -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 60" || true)',
        'MY_IP=$(curl -s --max-time 5 -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/public-ipv4 || true)',
        '# Wait up to 5 minutes for DNS to point to this instance',
        'if [ -n "$MY_IP" ]; then',
        '  for i in $(seq 1 30); do',
        '    RESOLVED=$(dig +short "$DOMAIN" @8.8.8.8 | tail -1)',
        '    if [ "$RESOLVED" = "$MY_IP" ]; then',
        '      echo "DNS resolved $DOMAIN → $RESOLVED (matches this instance)"',
        '      break',
        '    fi',
        '    echo "Waiting for DNS... $DOMAIN → $RESOLVED (expected $MY_IP) [attempt $i/30]"',
        '    sleep 10',
        '  done',
        'else',
        '  echo "Could not determine public IP — skipping DNS wait, attempting certbot directly"',
        'fi',
        'systemctl start nginx',
        'certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email --redirect',
        'systemctl reload nginx',
        'echo "TLS setup complete for $DOMAIN — cert auto-renews via certbot systemd timer"',
        'SETUP_END',
        'chmod +x /usr/local/bin/setup-tls',
        '',
        // auto-tls systemd service: runs setup-tls on boot after network is up
        `cat > /etc/systemd/system/setup-tls.service << 'TLS_SVC_END'`,
        '[Unit]',
        'Description=Auto-provision TLS certificate',
        'After=network-online.target nginx.service ambonmud.service',
        'Wants=network-online.target',
        '',
        '[Service]',
        'Type=oneshot',
        'ExecStart=/usr/local/bin/setup-tls',
        'RemainAfterExit=true',
        '',
        '[Install]',
        'WantedBy=multi-user.target',
        'TLS_SVC_END',
        'systemctl daemon-reload',
        // `enable` wires setup-tls.service into multi-user.target via a .wants
        // symlink, so it will auto-run on future boots. BUT on this first
        // boot, cloud-init is already running inside the multi-user.target
        // activation transaction — enabling a new unit here does NOT
        // retroactively pull it in, so without an explicit `start` the unit
        // just sits in "inactive (dead)" forever and the operator has to
        // `sudo setup-tls` manually after the first deploy. The --no-block
        // flag lets user-data keep running while setup-tls's 5-minute DNS
        // wait loop runs in the background; cloud-init's total time budget
        // stays sane even if DNS propagation is slow.
        'systemctl enable setup-tls',
        '',
        // bind-utils (for dig, used by setup-tls to wait on DNS) is installed
        // earlier in the top-level dnf call — no longer needed here.
        'systemctl start nginx',
        'systemctl start --no-block setup-tls',
      );
    }

    // Start ambonmud last, with --no-block. If its ExecStartPre chain is slow
    // (world zone fetches hitting R2 retries, cert fetch from SSM, etc.) a
    // blocking start can exceed cloud-init's scripts-user timeout and abort
    // all subsequent userData. The service has Restart=always so systemd will
    // keep retrying if anything in the startup chain fails transiently.
    userData.addCommands('systemctl start --no-block ambonmud prometheus grafana');

    // -------------------------------------------------------------------------
    // EC2 instance: t4g.micro — ARM64, 2 vCPU (burstable) / 1 GB RAM.
    // Upgraded from t4g.nano to accommodate Prometheus + Grafana containers
    // alongside the main AmbonMUD container.
    // -------------------------------------------------------------------------
    const instance = new ec2.Instance(this, 'Instance', {
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO),
      machineImage: ec2.MachineImage.latestAmazonLinux2023({
        cpuType: ec2.AmazonLinuxCpuType.ARM_64,
      }),
      securityGroup: sg,
      role,
      userData,
      blockDevices: [
        {
          deviceName: '/dev/xvda',
          volume: ec2.BlockDeviceVolume.ebs(16, {
            volumeType: ec2.EbsDeviceVolumeType.GP3,
            encrypted: true,
          }),
        },
      ],
    });

    // Elastic IP: persists across instance stop/start; free while attached.
    const eip = new ec2.CfnEIP(this, 'Eip', {
      instanceId: instance.instanceId,
      tags: [{ key: 'Name', value: 'AmbonMUD' }],
    });

    // -------------------------------------------------------------------------
    // Optional Route 53 A record: play.<domain> → EIP
    // -------------------------------------------------------------------------
    if (domain) {
      const zone = route53.HostedZone.fromLookup(this, 'Zone', { domainName: domain });
      new route53.ARecord(this, 'DnsA', {
        zone,
        recordName: `play.${domain}`,
        target: route53.RecordTarget.fromIpAddresses(eip.attrPublicIp),
        ttl: Duration.minutes(5),
      });
    }

    // -------------------------------------------------------------------------
    // Outputs
    // -------------------------------------------------------------------------
    new CfnOutput(this, 'PublicIp', {
      value: eip.attrPublicIp,
      description: 'Server public IP',
    });
    new CfnOutput(this, 'InstanceId', {
      value: instance.instanceId,
      description: 'EC2 instance ID',
    });
    new CfnOutput(this, 'TelnetConnect', {
      value: `telnet ${eip.attrPublicIp} 4000`,
      description: 'Connect via telnet',
    });
    new CfnOutput(this, 'WebConnect', {
      value: hostname ? `https://${hostname}` : `http://${eip.attrPublicIp}:8080`,
      description: 'Connect via web browser',
    });
    new CfnOutput(this, 'SsmShell', {
      value: `aws ssm start-session --target ${instance.instanceId} --region ${this.region}`,
      description: 'Open a shell on the instance (no SSH key required)',
    });
    new CfnOutput(this, 'UpdateImage', {
      value: `aws ssm send-command --instance-ids ${instance.instanceId} --document-name AWS-RunShellScript --parameters 'commands=["update-ambonmud <new-tag>"]' --region ${this.region}`,
      description: 'Deploy a new image tag without replacing the instance',
    });
    if (hostname) {
      new CfnOutput(this, 'SetupTls', {
        value: `aws ssm send-command --instance-ids ${instance.instanceId} --document-name AWS-RunShellScript --parameters 'commands=["setup-tls"]' --region ${this.region}`,
        description: 'Provision Let\'s Encrypt cert (run once after DNS is live)',
      });
    }
  }
}
