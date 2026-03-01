#!/usr/bin/env node
import 'source-map-support/register';
import { App, Environment } from 'aws-cdk-lib';
import { resolveConfig } from '../lib/config';
import { VpcStack } from '../lib/vpc-stack';
import { DataStack } from '../lib/data-stack';
import { LbStack } from '../lib/lb-stack';
import { EcsStack } from '../lib/ecs-stack';
import { DnsStack } from '../lib/dns-stack';
import { MonitoringStack } from '../lib/monitoring-stack';

const app = new App();

// ---------------------------------------------------------------------------
// Resolve deployment configuration from CDK context flags.
// Usage:
//   cdk deploy --all --context topology=standalone --context tier=hobby
//   cdk deploy --all --context topology=split --context tier=moderate
//   cdk deploy --all --context topology=split --context tier=production \
//              --context domain=example.com --context alertEmail=ops@example.com
// ---------------------------------------------------------------------------
const config = resolveConfig(app);
const domain = app.node.tryGetContext('domain') as string | undefined;

// AWS account + region resolved from environment or CDK_DEFAULT_* env vars.
const env: Environment = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION ?? 'us-east-1',
};

// ECR image tag (git SHA) passed via context or defaults to "latest" for local testing.
const imageTag = (app.node.tryGetContext('imageTag') as string | undefined) ?? 'latest';
const ecrRepoName = (app.node.tryGetContext('ecrRepo') as string | undefined) ?? 'ambonmud/app';

const stackPrefix = `AmbonMUD-${config.topology}-${config.tier}`;

// ---------------------------------------------------------------------------
// Stack instantiation order (each depends on the previous)
// ---------------------------------------------------------------------------
const vpcStack = new VpcStack(app, `${stackPrefix}-Vpc`, { env, config });

const dataStack = new DataStack(app, `${stackPrefix}-Data`, {
  env,
  config,
  vpcStack,
});
dataStack.addDependency(vpcStack);

const lbStack = new LbStack(app, `${stackPrefix}-Lb`, {
  env,
  config,
  vpcStack,
});
lbStack.addDependency(vpcStack);

const ecsStack = new EcsStack(app, `${stackPrefix}-Ecs`, {
  env,
  config,
  imageTag,
  ecrRepoName,
  vpcStack,
  dataStack,
  lbStack,
});
ecsStack.addDependency(dataStack);
ecsStack.addDependency(lbStack);

const dnsStack = new DnsStack(app, `${stackPrefix}-Dns`, {
  env,
  domain,
  lbStack,
});
dnsStack.addDependency(lbStack);

const monitoringStack = new MonitoringStack(app, `${stackPrefix}-Monitoring`, {
  env,
  config,
  dataStack,
  ecsStack,
});
monitoringStack.addDependency(ecsStack);

app.synth();
