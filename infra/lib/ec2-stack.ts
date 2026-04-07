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

    const { imageTag, ecrRepoName, domain, hostname, loreConfigUrl, worldZonesBaseUrl } = props;
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

    // -------------------------------------------------------------------------
    // User data: install Docker, write systemd service, install update helper.
    //
    // userDataCausesReplacement is left at its default (false) intentionally:
    // CDK changes to user data will NOT replace the instance and won't
    // clobber player YAML data on disk. To roll out a new image tag, run
    // `update-ambonmud <tag>` via SSM Session Manager.
    // -------------------------------------------------------------------------
    const userData = ec2.UserData.forLinux();
    userData.addCommands(
      'set -euo pipefail',
      'dnf install -y docker amazon-ssm-agent emacs-nox',
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
            `BASE_URL="${worldZonesBaseUrl}"`,
            '# Parse "- world/<name>.yaml" entries from the resources list',
            'grep -E "^\\s*-\\s*world/" "$CONFIG" | sed "s/.*- *//" | while read -r entry; do',
            '  filename="${entry#world/}"',
            '  echo "Fetching zone: $filename"',
            '  # --retry-all-errors is required for 404 retries: plain --retry only',
            '  # covers 5xx + connection errors. Cloudflare R2 edge POPs cache 404',
            '  # responses independently and can serve stale 404s for several minutes',
            '  # after an upload, so we spin in-place rather than failing the unit.',
            '  curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors \\',
            '    -o "/app/data/world/$filename" "$BASE_URL/$filename"',
            'done',
            'echo "World zones fetched to /app/data/world/"',
            'SCRIPT_END',
            'chmod +x /usr/local/bin/fetch-world-zones',
            '',
          ]
        : []),
      // ---- generate-htpasswd helper script ------------------------------------
      // Extracts the admin token from application-local.yaml and writes an
      // htpasswd file for nginx basic auth on /grafana/, /prometheus/, /admin/.
      // Called by ExecStartPre after the lore config is fetched.
      // ----------------------------------------------------------------------
      `cat > /usr/local/bin/generate-htpasswd << 'SCRIPT_END'`,
      '#!/bin/bash',
      'set -euo pipefail',
      'CONFIG=/app/data/application-local.yaml',
      'HTPASSWD=/etc/nginx/.htpasswd',
      'if [ ! -f "$CONFIG" ]; then',
      '  echo "No config overlay — skipping htpasswd generation"',
      '  exit 0',
      'fi',
      // Strip any surrounding double-quotes or single-quotes that YAML may put around the value.
      `TOKEN=$(grep -E "^\\s*token:" "$CONFIG" | head -1 | sed "s/.*token:\\s*//" | tr -d "\\"'")`,
      'if [ -z "$TOKEN" ]; then',
      '  echo "No admin token found in config — skipping htpasswd generation"',
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
      ...(loreConfigUrl
        ? [
            `ExecStartPre=/usr/bin/curl -fsSL --retry 20 --retry-delay 15 --retry-all-errors -o /app/data/application-local.yaml ${loreConfigUrl}`,
          ]
        : []),
      // Fetch world zone YAML files listed in the lore config's ambonmud.world.resources.
      // The helper script parses filenames from the config overlay, then curls each
      // from the base URL. The entrypoint places /app/data on the classpath before
      // the fat JAR, so these zones shadow the bundled placeholder zones.
      ...(worldZonesBaseUrl
        ? [`ExecStartPre=/usr/local/bin/fetch-world-zones`]
        : []),
      // Generate htpasswd for nginx basic auth on /grafana/, /prometheus/, /admin/.
      'ExecStartPre=/usr/local/bin/generate-htpasswd',
      `ExecStart=/usr/bin/docker run --name ambonmud --network ambonmud-net -p 4000:4000 -p 8080:8080 -p 9091:9091 -v /app/data:/app/data -e AMBONMUD_PERSISTENCE_BACKEND=YAML -e AMBONMUD_REDIS_ENABLED=false ${ecrUri}:${imageTag}`,
      'ExecStop=/usr/bin/docker stop ambonmud',
      '',
      '[Install]',
      'WantedBy=multi-user.target',
      'SERVICE_END',
      '',
      'systemctl daemon-reload',
      'systemctl enable ambonmud prometheus grafana',
      'systemctl start ambonmud prometheus grafana',
    );

    // -------------------------------------------------------------------------
    // Optional nginx + TLS setup (when hostname is provided).
    //
    // Installs nginx and certbot, writes an nginx reverse-proxy config that
    // handles both HTTP and WebSocket connections, and installs a `setup-tls`
    // helper that the operator runs once (via SSM) after DNS is live:
    //   setup-tls
    //
    // certbot uses HTTP-01 challenge so nginx must be running and reachable on
    // port 80 before running setup-tls.
    // -------------------------------------------------------------------------
    if (hostname) {
      userData.addCommands(
        '',
        'dnf install -y nginx certbot python3-certbot-nginx httpd-tools',
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
        'systemctl enable setup-tls',
        '',
        'dnf install -y bind-utils',  // for dig
        'systemctl start nginx',
      );
    }

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
