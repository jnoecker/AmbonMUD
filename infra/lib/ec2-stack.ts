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
}

/**
 * Minimal single-instance EC2 deployment (~$4-5/mo).
 *
 * Provisions:
 *   - VPC with a single public subnet (no NAT gateway)
 *   - Security group: TCP 4000 (telnet) + 8080 (web) open to 0.0.0.0/0
 *   - IAM role: ECR pull + SSM Session Manager (no SSH key needed)
 *   - t4g.nano (ARM64) running Amazon Linux 2023
 *   - Docker + systemd service that pulls and runs the AmbonMUD container
 *   - Elastic IP (persists across stop/start; no charge while attached)
 *   - Optional Route 53 A record (play.<domain> → EIP)
 *
 * YAML persistence is used (no RDS or Redis).
 * Player data lives at /app/data on the root EBS volume.
 *
 * To deploy a new image tag without replacing the instance (and losing
 * player YAML data), run the update helper via SSM:
 *   aws ssm start-session --target <instanceId>
 *   $ update-ambonmud <new-tag>
 */
export class Ec2Stack extends Stack {
  constructor(scope: Construct, id: string, props: Ec2StackProps) {
    super(scope, id, props);

    const { imageTag, ecrRepoName, domain } = props;
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
    sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(8080), 'Web / WebSocket');
    sg.addIngressRule(ec2.Peer.anyIpv6(), ec2.Port.tcp(8080), 'Web / WebSocket IPv6');

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
      'dnf update -y',
      'dnf install -y docker',
      'systemctl enable --now docker',
      'mkdir -p /app/data',
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
      `ExecStart=/usr/bin/docker run --name ambonmud -p 4000:4000 -p 8080:8080 -v /app/data:/app/data -e AMBONMUD_PERSISTENCE_BACKEND=YAML -e AMBONMUD_REDIS_ENABLED=false ${ecrUri}:${imageTag}`,
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
    // EC2 instance: t4g.nano — ARM64, 2 vCPU (burstable) / 512 MB RAM.
    // Load testing showed AmbonMUD uses ~40 MB heap at 150 players; 512 MB is
    // ample headroom. JVM ergonomics caps heap at ~128 MB on 512 MB system.
    // -------------------------------------------------------------------------
    const instance = new ec2.Instance(this, 'Instance', {
      vpc,
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
          volume: ec2.BlockDeviceVolume.ebs(8, {
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
      value: `http://${eip.attrPublicIp}:8080`,
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
  }
}
