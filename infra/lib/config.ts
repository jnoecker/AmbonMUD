import { App } from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';

export type Topology = 'standalone' | 'split';
export type ScaleTier = 'hobby' | 'moderate' | 'production';

export interface InfraConfig {
  readonly topology: Topology;
  readonly tier: ScaleTier;

  // VPC
  readonly azCount: number;
  readonly natGateways: number;

  // RDS
  readonly rdsInstanceType: ec2.InstanceType;
  readonly rdsMultiAz: boolean;
  readonly rdsBackupRetentionDays: number;

  // ElastiCache Redis
  readonly redisNodeType: string;
  readonly redisNumShards: number;
  readonly redisReplicasPerShard: number;

  // Standalone task sizing (topology=standalone only)
  readonly standaloneCpu: number;
  readonly standaloneMemory: number;

  // Engine task sizing (topology=split only)
  readonly engineCpu: number;
  readonly engineMemory: number;
  readonly engineMinTasks: number;
  readonly engineMaxTasks: number;

  // Gateway task sizing (topology=split only)
  readonly gatewayCpu: number;
  readonly gatewayMemory: number;
  readonly gatewayMinTasks: number;
  readonly gatewayMaxTasks: number;

  // Monitoring
  readonly enableSnsAlerts: boolean;
  readonly alertEmail?: string;
}

/** Tier-specific sizing table. */
const TIER_CONFIG: Record<ScaleTier, Omit<InfraConfig, 'topology' | 'tier'>> = {
  hobby: {
    azCount: 1,
    natGateways: 1,
    rdsInstanceType: ec2.InstanceType.of(ec2.InstanceClass.T3, ec2.InstanceSize.MICRO),
    rdsMultiAz: false,
    rdsBackupRetentionDays: 3,
    redisNodeType: 'cache.t3.micro',
    redisNumShards: 1,
    redisReplicasPerShard: 0,
    standaloneCpu: 512,
    standaloneMemory: 1024,
    engineCpu: 512,
    engineMemory: 1024,
    engineMinTasks: 1,
    engineMaxTasks: 1,
    gatewayCpu: 256,
    gatewayMemory: 512,
    gatewayMinTasks: 1,
    gatewayMaxTasks: 1,
    enableSnsAlerts: false,
  },
  moderate: {
    azCount: 2,
    natGateways: 1,
    rdsInstanceType: ec2.InstanceType.of(ec2.InstanceClass.T3, ec2.InstanceSize.MEDIUM),
    rdsMultiAz: true,
    rdsBackupRetentionDays: 7,
    redisNodeType: 'cache.t3.small',
    redisNumShards: 1,
    redisReplicasPerShard: 1,
    standaloneCpu: 1024,
    standaloneMemory: 2048,
    engineCpu: 1024,
    engineMemory: 2048,
    engineMinTasks: 1,
    engineMaxTasks: 2,
    gatewayCpu: 512,
    gatewayMemory: 1024,
    gatewayMinTasks: 1,
    gatewayMaxTasks: 3,
    enableSnsAlerts: true,
  },
  production: {
    azCount: 3,
    natGateways: 3,
    rdsInstanceType: ec2.InstanceType.of(ec2.InstanceClass.R6G, ec2.InstanceSize.LARGE),
    rdsMultiAz: true,
    rdsBackupRetentionDays: 14,
    redisNodeType: 'cache.r6g.large',
    redisNumShards: 3,
    redisReplicasPerShard: 1,
    standaloneCpu: 2048,
    standaloneMemory: 4096,
    engineCpu: 2048,
    engineMemory: 4096,
    engineMinTasks: 1,
    engineMaxTasks: 6,
    gatewayCpu: 1024,
    gatewayMemory: 2048,
    gatewayMinTasks: 1,
    gatewayMaxTasks: 8,
    enableSnsAlerts: true,
  },
};

export function resolveConfig(app: App): InfraConfig {
  const topology = (app.node.tryGetContext('topology') ?? 'standalone') as Topology;
  const tier = (app.node.tryGetContext('tier') ?? 'hobby') as ScaleTier;
  const alertEmail = app.node.tryGetContext('alertEmail') as string | undefined;

  if (topology !== 'standalone' && topology !== 'split') {
    throw new Error(`Invalid topology "${topology}". Must be "standalone" or "split".`);
  }
  if (!(tier in TIER_CONFIG)) {
    throw new Error(`Invalid tier "${tier}". Must be "hobby", "moderate", or "production".`);
  }

  return {
    topology,
    tier,
    alertEmail,
    ...TIER_CONFIG[tier],
  };
}
