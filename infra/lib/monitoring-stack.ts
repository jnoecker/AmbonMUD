import { Duration, Stack, StackProps } from 'aws-cdk-lib';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as actions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as subscriptions from 'aws-cdk-lib/aws-sns-subscriptions';
import { Construct } from 'constructs';
import { DataStack } from './data-stack';
import { EcsStack } from './ecs-stack';
import { InfraConfig } from './config';

export interface MonitoringStackProps extends StackProps {
  readonly config: InfraConfig;
  readonly dataStack: DataStack;
  readonly ecsStack: EcsStack;
}

export class MonitoringStack extends Stack {
  readonly alertTopic?: sns.Topic;

  constructor(scope: Construct, id: string, props: MonitoringStackProps) {
    super(scope, id, props);

    const { config, dataStack, ecsStack } = props;

    // -------------------------------------------------------------------------
    // SNS alert topic (moderate+ tiers)
    // -------------------------------------------------------------------------
    if (config.enableSnsAlerts) {
      this.alertTopic = new sns.Topic(this, 'AlertTopic', {
        displayName: 'AmbonMUD Alerts',
      });

      if (config.alertEmail) {
        this.alertTopic.addSubscription(
          new subscriptions.EmailSubscription(config.alertEmail),
        );
      }
    }

    // -------------------------------------------------------------------------
    // RDS alarms
    // -------------------------------------------------------------------------
    this.alarm('RdsCpu', {
      metric: new cloudwatch.Metric({
        namespace: 'AWS/RDS',
        metricName: 'CPUUtilization',
        dimensionsMap: {
          DBInstanceIdentifier: dataStack.dbInstance.instanceIdentifier,
        },
        statistic: 'Average',
        period: Duration.minutes(5),
      }),
      threshold: 80,
      evaluationPeriods: 3,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      alarmDescription: 'RDS CPU > 80% for 15 minutes',
    });

    this.alarm('RdsFreeStorage', {
      metric: new cloudwatch.Metric({
        namespace: 'AWS/RDS',
        metricName: 'FreeStorageSpace',
        dimensionsMap: {
          DBInstanceIdentifier: dataStack.dbInstance.instanceIdentifier,
        },
        statistic: 'Average',
        period: Duration.minutes(5),
      }),
      // Alert at < 5 GB free
      threshold: 5 * 1024 * 1024 * 1024,
      evaluationPeriods: 2,
      comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
      alarmDescription: 'RDS free storage < 5 GB',
    });

    // -------------------------------------------------------------------------
    // ElastiCache Redis alarms
    // -------------------------------------------------------------------------
    this.alarm('RedisEvictions', {
      metric: new cloudwatch.Metric({
        namespace: 'AWS/ElastiCache',
        metricName: 'Evictions',
        dimensionsMap: {
          ReplicationGroupId: dataStack.redisReplicationGroup.ref,
        },
        statistic: 'Sum',
        period: Duration.minutes(5),
      }),
      threshold: 100,
      evaluationPeriods: 2,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      alarmDescription: 'Redis evictions indicate memory pressure',
    });

    // -------------------------------------------------------------------------
    // ECS cluster alarms (log metric filters for app-level events)
    // -------------------------------------------------------------------------

    // Engine: player save failures (log filter on ERROR containing "save")
    const engineLogGroup = logs.LogGroup.fromLogGroupName(
      this, 'EngineLogGroup', '/ambonmud/engine',
    );

    const saveFail = new logs.MetricFilter(this, 'PlayerSaveFailMetric', {
      logGroup: engineLogGroup,
      metricNamespace: 'AmbonMUD',
      metricName: 'PlayerSaveFailures',
      filterPattern: logs.FilterPattern.all(
        logs.FilterPattern.stringValue('$.level', '=', 'ERROR'),
        logs.FilterPattern.stringValue('$.message', '=', '*save*'),
      ),
      metricValue: '1',
      defaultValue: 0,
    });

    this.alarm('PlayerSaveFailures', {
      metric: saveFail.metric({
        period: Duration.minutes(5),
        statistic: 'Sum',
      }),
      threshold: 3,
      evaluationPeriods: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
      alarmDescription: 'Multiple player save failures in 5 minutes',
    });

    // ECS service CPU alarms
    this.alarm('ClusterHighCpu', {
      metric: new cloudwatch.Metric({
        namespace: 'AWS/ECS',
        metricName: 'CPUUtilization',
        dimensionsMap: {
          ClusterName: ecsStack.cluster.clusterName,
        },
        statistic: 'Average',
        period: Duration.minutes(5),
      }),
      threshold: 85,
      evaluationPeriods: 3,
      comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
      alarmDescription: 'ECS cluster average CPU > 85% sustained',
    });
  }

  private alarm(
    id: string,
    props: {
      metric: cloudwatch.IMetric;
      threshold: number;
      evaluationPeriods: number;
      comparisonOperator: cloudwatch.ComparisonOperator;
      alarmDescription: string;
    },
  ): cloudwatch.Alarm {
    const alarm = new cloudwatch.Alarm(this, `Alarm${id}`, {
      ...props,
      actionsEnabled: this.alertTopic !== undefined,
    });

    if (this.alertTopic) {
      alarm.addAlarmAction(new actions.SnsAction(this.alertTopic));
      alarm.addOkAction(new actions.SnsAction(this.alertTopic));
    }

    return alarm;
  }
}
