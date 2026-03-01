import { Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as efs from 'aws-cdk-lib/aws-efs';
import * as elasticache from 'aws-cdk-lib/aws-elasticache';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';
import { InfraConfig } from './config';
import { VpcStack } from './vpc-stack';

export interface DataStackProps extends StackProps {
  readonly config: InfraConfig;
  readonly vpcStack: VpcStack;
}

export class DataStack extends Stack {
  readonly dbSecret: secretsmanager.Secret;
  readonly dbInstance: rds.DatabaseInstance;
  readonly redisReplicationGroup: elasticache.CfnReplicationGroup;
  readonly redisSubnetGroup: elasticache.CfnSubnetGroup;
  readonly fileSystem: efs.FileSystem;
  readonly adminTokenSecret: secretsmanager.Secret;

  /** Redis primary endpoint (host:port as a single string for the app config). */
  readonly redisEndpointAddress: string;
  readonly redisEndpointPort: string;

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    const { config, vpcStack } = props;
    const vpc = vpcStack.vpc;
    const isolatedSubnets = vpc.selectSubnets({ subnetType: ec2.SubnetType.PRIVATE_ISOLATED });

    // -------------------------------------------------------------------------
    // RDS PostgreSQL
    // -------------------------------------------------------------------------
    this.dbSecret = new secretsmanager.Secret(this, 'DbSecret', {
      description: 'AmbonMUD Postgres credentials',
      generateSecretString: {
        secretStringTemplate: JSON.stringify({ username: 'ambonmud' }),
        generateStringKey: 'password',
        excludePunctuation: true,
        passwordLength: 32,
      },
    });

    const dbEngine = rds.DatabaseInstanceEngine.postgres({
      version: rds.PostgresEngineVersion.VER_16,
    });

    this.dbInstance = new rds.DatabaseInstance(this, 'Db', {
      engine: dbEngine,
      instanceType: config.rdsInstanceType,
      vpc,
      vpcSubnets: isolatedSubnets,
      securityGroups: [vpcStack.sgRds],
      credentials: rds.Credentials.fromSecret(this.dbSecret),
      databaseName: 'ambonmud',
      multiAz: config.rdsMultiAz,
      storageEncrypted: true,
      backupRetention: Duration.days(config.rdsBackupRetentionDays),
      deletionProtection: config.tier !== 'hobby',
      removalPolicy: config.tier === 'hobby' ? RemovalPolicy.DESTROY : RemovalPolicy.RETAIN,
      enablePerformanceInsights: config.tier !== 'hobby',
      autoMinorVersionUpgrade: true,
    });

    // -------------------------------------------------------------------------
    // ElastiCache Redis
    // -------------------------------------------------------------------------
    this.redisSubnetGroup = new elasticache.CfnSubnetGroup(this, 'RedisSubnetGroup', {
      description: 'AmbonMUD Redis subnet group',
      subnetIds: isolatedSubnets.subnetIds,
    });

    const clusterModeEnabled = config.redisNumShards > 1;

    this.redisReplicationGroup = new elasticache.CfnReplicationGroup(this, 'Redis', {
      replicationGroupDescription: 'AmbonMUD Redis bus + zone registry',
      cacheNodeType: config.redisNodeType,
      engine: 'redis',
      engineVersion: '7.1',
      numNodeGroups: config.redisNumShards,
      replicasPerNodeGroup: config.redisReplicasPerShard,
      automaticFailoverEnabled: config.redisReplicasPerShard > 0,
      multiAzEnabled: config.rdsMultiAz,
      cacheSubnetGroupName: this.redisSubnetGroup.ref,
      securityGroupIds: [vpcStack.sgRedis.securityGroupId],
      atRestEncryptionEnabled: true,
      transitEncryptionEnabled: false, // App uses plain redis:// URI; enable TLS if needed
      clusterMode: clusterModeEnabled ? 'enabled' : 'disabled',
    });

    // Configuration endpoint for cluster mode, primary endpoint for single-shard
    this.redisEndpointAddress = clusterModeEnabled
      ? this.redisReplicationGroup.attrConfigurationEndPointAddress
      : this.redisReplicationGroup.attrPrimaryEndPointAddress;
    this.redisEndpointPort = clusterModeEnabled
      ? this.redisReplicationGroup.attrConfigurationEndPointPort
      : this.redisReplicationGroup.attrPrimaryEndPointPort;

    // -------------------------------------------------------------------------
    // EFS (world mutations persistent storage)
    // -------------------------------------------------------------------------
    this.fileSystem = new efs.FileSystem(this, 'Efs', {
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroup: vpcStack.sgEfs,
      encrypted: true,
      performanceMode: efs.PerformanceMode.GENERAL_PURPOSE,
      throughputMode: efs.ThroughputMode.BURSTING,
      removalPolicy: config.tier === 'hobby' ? RemovalPolicy.DESTROY : RemovalPolicy.RETAIN,
    });

    // -------------------------------------------------------------------------
    // Secrets Manager: admin token
    // -------------------------------------------------------------------------
    this.adminTokenSecret = new secretsmanager.Secret(this, 'AdminTokenSecret', {
      description: 'AmbonMUD admin HTTP token',
      generateSecretString: {
        excludePunctuation: true,
        passwordLength: 40,
      },
    });
  }
}
