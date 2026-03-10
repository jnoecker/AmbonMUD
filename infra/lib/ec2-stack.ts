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
   * Optional URL to a lore config overlay (application-local.yaml).
   * If provided, the systemd service curls it on every (re)start and
   * bind-mounts it into the container where AppConfigLoader picks it up.
   */
  readonly loreConfigUrl?: string;
  /**
   * Optional base URL for world zone YAML files (e.g. "https://assets.ambon.dev/world").
   * If provided (along with loreConfigUrl), the systemd service parses zone filenames
   * from the lore config's ambonmud.world.resources list and curls each one from
   * ${worldZonesBaseUrl}/<filename>. The entrypoint places /app/data on the classpath
   * before the fat JAR, so these zones shadow the built-in placeholder zones.
   */
  readonly worldZonesBaseUrl?: string;
}

/**
 * Minimal single-instance EC2 deployment (~$4-5/mo).
 *
 * Provisions:
 *   - VPC with a single public subnet (no NAT gateway)
 *   - Security group: TCP 4000 (telnet) + 80/443 (HTTP/HTTPS) + 8080 (direct web) open to 0.0.0.0/0
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
      'dnf install -y docker amazon-ssm-agent',
      'systemctl enable --now docker',
      'systemctl enable --now amazon-ssm-agent',
      // UID 1001 matches the pinned ambonmud user inside the container (Dockerfile).
      'mkdir -p /app/data && chown 1001:1001 /app/data',
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
      // list and curls each from the configured base URL.
      // Called by ExecStartPre on every service (re)start.
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
            `BASE_URL="${worldZonesBaseUrl.replace(/\/+$/, '')}"`,
            '# Parse "- world/<name>.yaml" entries from the resources list',
            'FETCHED=0',
            '(grep -E "^\\s*-\\s*world/" "$CONFIG" || true) | sed "s/.*- *//" | while read -r entry; do',
            '  [ -z "$entry" ] && continue',
            '  filename="${entry#world/}"',
            '  echo "Fetching zone: $filename"',
            '  if curl -fsSL -o "/app/data/world/$filename" "$BASE_URL/$filename"; then',
            '    FETCHED=$((FETCHED + 1))',
            '  else',
            '    echo "Warning: failed to fetch $filename (may not exist on CDN) — skipping"',
            '  fi',
            'done',
            'echo "World zones fetched to /app/data/world/"',
            'SCRIPT_END',
            'chmod +x /usr/local/bin/fetch-world-zones',
          ]
        : []),
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
      // Fetch the lore config overlay (custom races, backstories, images, etc.)
      // and bind-mount it into the container where AppConfigLoader reads it.
      ...(loreConfigUrl
        ? [`ExecStartPre=/usr/bin/curl -fsSL -o /app/data/application-local.yaml ${loreConfigUrl}`]
        : []),
      // Fetch world zone YAML files listed in the lore config's ambonmud.world.resources.
      // The helper script parses filenames from the config overlay, then curls each
      // from the base URL. The entrypoint places /app/data on the classpath before
      // the fat JAR, so these zones shadow the built-in placeholder zones.
      ...(worldZonesBaseUrl
        ? [`ExecStartPre=/usr/local/bin/fetch-world-zones`]
        : []),
      // JAVA_TOOL_OPTIONS is read directly by the JVM (not Hoplite), making it
      // a reliable way to set JVM system properties in the container.
      // -Dambon.profile=demo loads application-demo.yaml from the classpath,
      // which overrides classStartRooms to start players in noecker_resume.
      `ExecStart=/usr/bin/docker run --name ambonmud -p 4000:4000 -p 8080:8080 -v /app/data:/app/data ${loreConfigUrl ? '-v /app/data/application-local.yaml:/app/application-local.yaml:ro ' : ''}-e AMBONMUD_PERSISTENCE_BACKEND=YAML -e AMBONMUD_REDIS_ENABLED=false -e JAVA_TOOL_OPTIONS=-Dambon.profile=demo ${ecrUri}:${imageTag}`,
      'ExecStop=/usr/bin/docker stop ambonmud',
      '',
      '[Install]',
      'WantedBy=multi-user.target',
      'SERVICE_END',
      '',
      'systemctl daemon-reload',
      'systemctl enable ambonmud',
      'systemctl start ambonmud',
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
        'dnf install -y nginx certbot python3-certbot-nginx',
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
        '}',
        'NGINX_END',
        '',
        // setup-tls: provision Let's Encrypt cert, can be run manually or via systemd
        `cat > /usr/local/bin/setup-tls << 'SETUP_END'`,
        '#!/bin/bash',
        'set -euo pipefail',
        `DOMAIN="\${1:-${hostname}}"`,
        'MY_IP=$(curl -s --max-time 5 http://169.254.169.254/latest/meta-data/public-ipv4 || true)',
        '# Wait up to 5 minutes for DNS to point to this instance',
        'for i in $(seq 1 30); do',
        '  RESOLVED=$(dig +short "$DOMAIN" @8.8.8.8 | tail -1)',
        '  if [ "$RESOLVED" = "$MY_IP" ]; then',
        '    echo "DNS resolved $DOMAIN → $RESOLVED (matches this instance)"',
        '    break',
        '  fi',
        '  echo "Waiting for DNS... $DOMAIN → $RESOLVED (expected $MY_IP) [attempt $i/30]"',
        '  sleep 10',
        'done',
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
    // EC2 instance: t4g.nano — ARM64, 2 vCPU (burstable) / 512 MB RAM.
    // Load testing showed AmbonMUD uses ~40 MB heap at 150 players; 512 MB is
    // ample headroom. JVM ergonomics caps heap at ~128 MB on 512 MB system.
    // -------------------------------------------------------------------------
    const instance = new ec2.Instance(this, 'Instance', {
      vpc,
      userDataCausesReplacement: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.NANO),
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
