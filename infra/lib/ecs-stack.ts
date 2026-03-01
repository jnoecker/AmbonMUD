import { Duration, Stack, StackProps } from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as efs from 'aws-cdk-lib/aws-efs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import { Construct } from 'constructs';
import { DataStack } from './data-stack';
import { LbStack } from './lb-stack';
import { VpcStack } from './vpc-stack';
import { InfraConfig } from './config';

export interface EcsStackProps extends StackProps {
  readonly config: InfraConfig;
  /** ECR image tag to deploy (git SHA). */
  readonly imageTag: string;
  /** ECR repository name (e.g. "ambonmud/app"). */
  readonly ecrRepoName: string;
  readonly vpcStack: VpcStack;
  readonly dataStack: DataStack;
  readonly lbStack: LbStack;
}

export class EcsStack extends Stack {
  readonly cluster: ecs.Cluster;
  readonly ecrRepo: ecr.IRepository;

  constructor(scope: Construct, id: string, props: EcsStackProps) {
    super(scope, id, props);

    const { config, imageTag, ecrRepoName, vpcStack, dataStack, lbStack } = props;
    const vpc = vpcStack.vpc;

    // -------------------------------------------------------------------------
    // ECR repository lookup
    // -------------------------------------------------------------------------
    this.ecrRepo = ecr.Repository.fromRepositoryName(this, 'EcrRepo', ecrRepoName);
    const containerImage = ecs.ContainerImage.fromEcrRepository(this.ecrRepo, imageTag);

    // -------------------------------------------------------------------------
    // ECS cluster with Container Insights
    // -------------------------------------------------------------------------
    this.cluster = new ecs.Cluster(this, 'Cluster', {
      vpc,
      containerInsightsV2: ecs.ContainerInsights.ENABLED,
    });

    // -------------------------------------------------------------------------
    // EFS access point for world mutations
    // -------------------------------------------------------------------------
    const efsAp = dataStack.fileSystem.addAccessPoint('AppAccessPoint', {
      path: '/ambonmud',
      createAcl: { ownerGid: '1000', ownerUid: '1000', permissions: '755' },
      posixUser: { gid: '1000', uid: '1000' },
    });

    // -------------------------------------------------------------------------
    // Shared execution role (ECR pull + Secrets Manager read)
    // -------------------------------------------------------------------------
    const executionRole = new iam.Role(this, 'ExecutionRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });
    dataStack.dbSecret.grantRead(executionRole);
    dataStack.adminTokenSecret.grantRead(executionRole);

    // Redis URI: internal VPC endpoint, no credentials needed — pass as plain env var.
    // CloudFormation tokens are resolved at deploy time.
    const redisUri = `redis://${dataStack.redisEndpointAddress}:${dataStack.redisEndpointPort}`;

    // JDBC URL: constructed from RDS endpoint tokens + fixed database name.
    // Credentials (username/password) are injected separately as secrets.
    const jdbcUrl =
      `jdbc:postgresql://${dataStack.dbInstance.dbInstanceEndpointAddress}` +
      `:${dataStack.dbInstance.dbInstanceEndpointPort}/ambonmud`;

    // -------------------------------------------------------------------------
    // Build topology
    // -------------------------------------------------------------------------
    if (config.topology === 'standalone') {
      this.createStandaloneService(
        vpc, containerImage, efsAp, executionRole,
        redisUri, jdbcUrl, config, dataStack, lbStack, vpcStack,
      );
    } else {
      this.createSplitServices(
        vpc, containerImage, efsAp, executionRole,
        redisUri, jdbcUrl, config, dataStack, lbStack, vpcStack,
      );
    }
  }

  // ---------------------------------------------------------------------------
  // Standalone topology
  // ---------------------------------------------------------------------------
  private createStandaloneService(
    _vpc: ec2.Vpc,
    image: ecs.ContainerImage,
    efsAp: efs.AccessPoint,
    executionRole: iam.Role,
    redisUri: string,
    jdbcUrl: string,
    config: InfraConfig,
    dataStack: DataStack,
    lbStack: LbStack,
    vpcStack: VpcStack,
  ): void {
    const taskRole = this.buildTaskRole('StandaloneTaskRole');
    dataStack.fileSystem.grantReadWrite(taskRole);

    const taskDef = new ecs.FargateTaskDefinition(this, 'StandaloneTaskDef', {
      cpu: config.standaloneCpu,
      memoryLimitMiB: config.standaloneMemory,
      executionRole,
      taskRole,
    });

    this.attachEfsVolume(taskDef, efsAp);

    const logGroup = new logs.LogGroup(this, 'StandaloneLogGroup', {
      logGroupName: '/ambonmud/standalone',
      retention: logs.RetentionDays.ONE_WEEK,
    });

    const container = taskDef.addContainer('app', {
      image,
      environment: {
        AMBONMUD_MODE: 'STANDALONE',
        AMBONMUD_PERSISTENCE_BACKEND: 'POSTGRES',
        AMBONMUD_REDIS_ENABLED: 'true',
        AMBONMUD_REDIS_BUS_ENABLED: 'false',
        AMBONMUD_REDIS_URI: redisUri,
        AMBONMUD_DATABASE_JDBCURL: jdbcUrl,
        AMBONMUD_SERVER_TELNETPORT: '4000',
        AMBONMUD_SERVER_WEBPORT: '8080',
      },
      secrets: this.buildDbSecrets(dataStack),
      logging: ecs.LogDrivers.awsLogs({ logGroup, streamPrefix: 'standalone' }),
      portMappings: [
        { containerPort: 4000, protocol: ecs.Protocol.TCP },
        { containerPort: 8080, protocol: ecs.Protocol.TCP },
      ],
      stopTimeout: Duration.seconds(60),
    });

    container.addMountPoints({
      containerPath: '/app/data',
      sourceVolume: 'efs-data',
      readOnly: false,
    });

    const service = new ecs.FargateService(this, 'StandaloneService', {
      cluster: this.cluster,
      taskDefinition: taskDef,
      desiredCount: 1,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [vpcStack.sgGateway],
      enableExecuteCommand: true,
    });

    service.attachToNetworkTargetGroup(lbStack.nlbTelnetTg);
    service.attachToApplicationTargetGroup(lbStack.albWebTg);
  }

  // ---------------------------------------------------------------------------
  // Split topology
  // ---------------------------------------------------------------------------
  private createSplitServices(
    vpc: ec2.Vpc,
    image: ecs.ContainerImage,
    efsAp: efs.AccessPoint,
    executionRole: iam.Role,
    redisUri: string,
    jdbcUrl: string,
    config: InfraConfig,
    dataStack: DataStack,
    lbStack: LbStack,
    vpcStack: VpcStack,
  ): void {
    this.createEngineService(
      vpc, image, efsAp, executionRole,
      redisUri, jdbcUrl, config, dataStack, lbStack, vpcStack,
    );
    this.createGatewayService(
      image, executionRole, redisUri, config, dataStack, lbStack, vpcStack,
    );
  }

  private createEngineService(
    _vpc: ec2.Vpc,
    image: ecs.ContainerImage,
    efsAp: efs.AccessPoint,
    executionRole: iam.Role,
    redisUri: string,
    jdbcUrl: string,
    config: InfraConfig,
    dataStack: DataStack,
    lbStack: LbStack,
    vpcStack: VpcStack,
  ): ecs.FargateService {
    const taskRole = this.buildTaskRole('EngineTaskRole');
    dataStack.fileSystem.grantReadWrite(taskRole);

    const taskDef = new ecs.FargateTaskDefinition(this, 'EngineTaskDef', {
      cpu: config.engineCpu,
      memoryLimitMiB: config.engineMemory,
      executionRole,
      taskRole,
    });

    this.attachEfsVolume(taskDef, efsAp);

    const logGroup = new logs.LogGroup(this, 'EngineLogGroup', {
      logGroupName: '/ambonmud/engine',
      retention: logs.RetentionDays.TWO_WEEKS,
    });

    const container = taskDef.addContainer('engine', {
      image,
      environment: {
        AMBONMUD_MODE: 'ENGINE',
        AMBONMUD_PERSISTENCE_BACKEND: 'POSTGRES',
        AMBONMUD_REDIS_ENABLED: 'true',
        AMBONMUD_REDIS_BUS_ENABLED: 'true',
        AMBONMUD_REDIS_URI: redisUri,
        AMBONMUD_DATABASE_JDBCURL: jdbcUrl,
        AMBONMUD_SHARDING_ENABLED: 'true',
        AMBONMUD_SHARDING_REGISTRY_TYPE: 'REDIS',
        AMBONMUD_GRPC_SERVER_PORT: '9090',
      },
      secrets: this.buildDbSecrets(dataStack),
      logging: ecs.LogDrivers.awsLogs({ logGroup, streamPrefix: 'engine' }),
      portMappings: [
        { containerPort: 9090, protocol: ecs.Protocol.TCP },
      ],
      stopTimeout: Duration.seconds(60),
    });

    container.addMountPoints({
      containerPath: '/app/data',
      sourceVolume: 'efs-data',
      readOnly: false,
    });

    const service = new ecs.FargateService(this, 'EngineService', {
      cluster: this.cluster,
      taskDefinition: taskDef,
      desiredCount: config.engineMinTasks,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [vpcStack.sgEngine],
      enableExecuteCommand: true,
      cloudMapOptions: {
        cloudMapNamespace: lbStack.namespace,
        name: 'engine',
        containerPort: 9090,
      },
    });

    const scaling = service.autoScaleTaskCount({
      minCapacity: config.engineMinTasks,
      maxCapacity: config.engineMaxTasks,
    });

    scaling.scaleOnCpuUtilization('EngineCpuScaling', {
      targetUtilizationPercent: 70,
      scaleInCooldown: Duration.minutes(5),
      scaleOutCooldown: Duration.minutes(2),
    });

    return service;
  }

  private createGatewayService(
    image: ecs.ContainerImage,
    executionRole: iam.Role,
    redisUri: string,
    config: InfraConfig,
    _dataStack: DataStack,
    lbStack: LbStack,
    vpcStack: VpcStack,
  ): ecs.FargateService {
    const taskRole = this.buildTaskRole('GatewayTaskRole');

    const taskDef = new ecs.FargateTaskDefinition(this, 'GatewayTaskDef', {
      cpu: config.gatewayCpu,
      memoryLimitMiB: config.gatewayMemory,
      executionRole,
      taskRole,
    });

    const logGroup = new logs.LogGroup(this, 'GatewayLogGroup', {
      logGroupName: '/ambonmud/gateway',
      retention: logs.RetentionDays.TWO_WEEKS,
    });

    taskDef.addContainer('gateway', {
      image,
      environment: {
        AMBONMUD_MODE: 'GATEWAY',
        AMBONMUD_REDIS_ENABLED: 'true',
        AMBONMUD_REDIS_BUS_ENABLED: 'true',
        AMBONMUD_REDIS_URI: redisUri,
        AMBONMUD_SERVER_TELNETPORT: '4000',
        AMBONMUD_SERVER_WEBPORT: '8080',
        AMBONMUD_GRPC_CLIENT_ENGINEHOST: 'engine.internal.ambonmud',
        AMBONMUD_GRPC_CLIENT_ENGINEPORT: '9090',
      },
      logging: ecs.LogDrivers.awsLogs({ logGroup, streamPrefix: 'gateway' }),
      portMappings: [
        { containerPort: 4000, protocol: ecs.Protocol.TCP },
        { containerPort: 8080, protocol: ecs.Protocol.TCP },
      ],
      stopTimeout: Duration.seconds(30),
    });

    const service = new ecs.FargateService(this, 'GatewayService', {
      cluster: this.cluster,
      taskDefinition: taskDef,
      desiredCount: config.gatewayMinTasks,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [vpcStack.sgGateway],
      enableExecuteCommand: true,
    });

    service.attachToNetworkTargetGroup(lbStack.nlbTelnetTg);
    service.attachToApplicationTargetGroup(lbStack.albWebTg);

    const scaling = service.autoScaleTaskCount({
      minCapacity: config.gatewayMinTasks,
      maxCapacity: config.gatewayMaxTasks,
    });

    scaling.scaleOnCpuUtilization('GatewayCpuScaling', {
      targetUtilizationPercent: 60,
      scaleInCooldown: Duration.minutes(10),
      scaleOutCooldown: Duration.minutes(1),
    });

    scaling.scaleOnRequestCount('GatewayRequestScaling', {
      requestsPerTarget: 500,
      targetGroup: lbStack.albWebTg,
      scaleInCooldown: Duration.minutes(5),
      scaleOutCooldown: Duration.minutes(1),
    });

    return service;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------
  private buildTaskRole(id: string): iam.Role {
    return new iam.Role(this, id, {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
  }

  private attachEfsVolume(taskDef: ecs.FargateTaskDefinition, ap: efs.AccessPoint): void {
    taskDef.addVolume({
      name: 'efs-data',
      efsVolumeConfiguration: {
        fileSystemId: ap.fileSystem.fileSystemId,
        transitEncryption: 'ENABLED',
        authorizationConfig: {
          accessPointId: ap.accessPointId,
          iam: 'ENABLED',
        },
      },
    });
  }

  private buildDbSecrets(dataStack: DataStack): Record<string, ecs.Secret> {
    // Inject the full JDBC URL as an env var constructed from secret fields.
    // The app reads AMBONMUD_DATABASE_JDBCURL via Hoplite env var source.
    return {
      AMBONMUD_DATABASE_USERNAME: ecs.Secret.fromSecretsManager(dataStack.dbSecret, 'username'),
      AMBONMUD_DATABASE_PASSWORD: ecs.Secret.fromSecretsManager(dataStack.dbSecret, 'password'),
    };
  }
}
