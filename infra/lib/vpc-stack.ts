import { Stack, StackProps } from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import { Construct } from 'constructs';
import { InfraConfig } from './config';

export interface VpcStackProps extends StackProps {
  readonly config: InfraConfig;
}

export class VpcStack extends Stack {
  readonly vpc: ec2.Vpc;

  // Security groups
  readonly sgGateway: ec2.SecurityGroup;
  readonly sgEngine: ec2.SecurityGroup;
  readonly sgRds: ec2.SecurityGroup;
  readonly sgRedis: ec2.SecurityGroup;
  readonly sgEfs: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props: VpcStackProps) {
    super(scope, id, props);

    const { config } = props;

    this.vpc = new ec2.Vpc(this, 'Vpc', {
      maxAzs: config.azCount,
      natGateways: config.natGateways,
      subnetConfiguration: [
        {
          name: 'Public',
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
        {
          name: 'Private',
          subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS,
          cidrMask: 24,
        },
        {
          name: 'Isolated',
          subnetType: ec2.SubnetType.PRIVATE_ISOLATED,
          cidrMask: 24,
        },
      ],
    });

    // Gateway: accepts telnet from internet (NLB), WebSocket from ALB
    this.sgGateway = new ec2.SecurityGroup(this, 'SgGateway', {
      vpc: this.vpc,
      description: 'AmbonMUD Gateway tasks',
      allowAllOutbound: true,
    });

    // Engine: accepts gRPC from Gateway only
    this.sgEngine = new ec2.SecurityGroup(this, 'SgEngine', {
      vpc: this.vpc,
      description: 'AmbonMUD Engine tasks',
      allowAllOutbound: true,
    });

    // RDS: accepts Postgres from Engine (and Standalone)
    this.sgRds = new ec2.SecurityGroup(this, 'SgRds', {
      vpc: this.vpc,
      description: 'AmbonMUD RDS Postgres',
      allowAllOutbound: false,
    });

    // Redis: accepts from Engine and Gateway
    this.sgRedis = new ec2.SecurityGroup(this, 'SgRedis', {
      vpc: this.vpc,
      description: 'AmbonMUD ElastiCache Redis',
      allowAllOutbound: false,
    });

    // EFS: accepts NFS from Engine (and Standalone)
    this.sgEfs = new ec2.SecurityGroup(this, 'SgEfs', {
      vpc: this.vpc,
      description: 'AmbonMUD EFS',
      allowAllOutbound: false,
    });

    // Gateway → Engine: gRPC port 9090
    this.sgEngine.addIngressRule(
      this.sgGateway,
      ec2.Port.tcp(9090),
      'Gateway → Engine gRPC',
    );

    // Engine → RDS: Postgres 5432
    this.sgRds.addIngressRule(
      this.sgEngine,
      ec2.Port.tcp(5432),
      'Engine → RDS',
    );

    // Standalone → RDS (when topology=standalone, the engine SG is reused)
    this.sgRds.addIngressRule(
      this.sgGateway,
      ec2.Port.tcp(5432),
      'Standalone → RDS',
    );

    // Engine → Redis: 6379
    this.sgRedis.addIngressRule(
      this.sgEngine,
      ec2.Port.tcp(6379),
      'Engine → Redis',
    );

    // Gateway → Redis: needed for Redis bus pub/sub
    this.sgRedis.addIngressRule(
      this.sgGateway,
      ec2.Port.tcp(6379),
      'Gateway → Redis',
    );

    // Engine → EFS: NFS 2049
    this.sgEfs.addIngressRule(
      this.sgEngine,
      ec2.Port.tcp(2049),
      'Engine → EFS NFS',
    );

    // Standalone → EFS
    this.sgEfs.addIngressRule(
      this.sgGateway,
      ec2.Port.tcp(2049),
      'Standalone → EFS NFS',
    );
  }
}
