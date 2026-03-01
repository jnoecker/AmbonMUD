import { Stack, StackProps } from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as servicediscovery from 'aws-cdk-lib/aws-servicediscovery';
import { Construct } from 'constructs';
import { InfraConfig } from './config';
import { VpcStack } from './vpc-stack';

export interface LbStackProps extends StackProps {
  readonly config: InfraConfig;
  readonly vpcStack: VpcStack;
}

export class LbStack extends Stack {
  /** NLB for raw TCP telnet on port 4000. */
  readonly nlb: elbv2.NetworkLoadBalancer;
  /** Target group: NLB → Gateway tasks, port 4000. */
  readonly nlbTelnetTg: elbv2.NetworkTargetGroup;

  /** ALB for WebSocket / HTTPS on port 443. */
  readonly alb: elbv2.ApplicationLoadBalancer;
  /** Target group: ALB → Gateway tasks, port 8080. */
  readonly albWebTg: elbv2.ApplicationTargetGroup;
  /** HTTP listener (redirects to HTTPS). */
  readonly httpListener: elbv2.ApplicationListener;
  /** HTTPS listener (TLS terminated at ALB). */
  readonly httpsListener: elbv2.ApplicationListener;

  /** Cloud Map private DNS namespace for service-to-service discovery. */
  readonly namespace: servicediscovery.PrivateDnsNamespace;
  /** Cloud Map service for Engine tasks (engine.internal.ambonmud). */
  readonly engineCloudMapService: servicediscovery.Service;

  constructor(scope: Construct, id: string, props: LbStackProps) {
    super(scope, id, props);

    const { vpcStack } = props;
    const vpc = vpcStack.vpc;

    // -------------------------------------------------------------------------
    // Cloud Map — internal DNS for Gateway → Engine gRPC discovery
    // -------------------------------------------------------------------------
    this.namespace = new servicediscovery.PrivateDnsNamespace(this, 'Namespace', {
      name: 'internal.ambonmud',
      vpc,
      description: 'AmbonMUD internal service discovery',
    });

    this.engineCloudMapService = this.namespace.createService('EngineService', {
      name: 'engine',
      dnsRecordType: servicediscovery.DnsRecordType.A,
      dnsTtl: undefined, // default 60s
      customHealthCheck: {
        failureThreshold: 1,
      },
    });

    // -------------------------------------------------------------------------
    // NLB — raw TCP telnet
    // -------------------------------------------------------------------------
    this.nlb = new elbv2.NetworkLoadBalancer(this, 'Nlb', {
      vpc,
      internetFacing: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });

    this.nlbTelnetTg = new elbv2.NetworkTargetGroup(this, 'NlbTelnetTg', {
      vpc,
      port: 4000,
      protocol: elbv2.Protocol.TCP,
      targetType: elbv2.TargetType.IP,
      healthCheck: {
        protocol: elbv2.Protocol.TCP,
        port: '4000',
        healthyThresholdCount: 2,
        unhealthyThresholdCount: 2,
      },
    });

    this.nlb.addListener('TelnetListener', {
      port: 4000,
      protocol: elbv2.Protocol.TCP,
      defaultTargetGroups: [this.nlbTelnetTg],
    });

    // -------------------------------------------------------------------------
    // ALB — WebSocket / HTTPS
    // NB: certificate must be provided via the dns-stack context after creation.
    // The HTTPS listener is created there; here we expose the HTTP redirect listener
    // and the WebSocket target group so ecs-stack can register targets.
    // -------------------------------------------------------------------------
    this.alb = new elbv2.ApplicationLoadBalancer(this, 'Alb', {
      vpc,
      internetFacing: true,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
    });

    // Allow inbound from internet on ports 80 and 443
    this.alb.addSecurityGroup(
      (() => {
        const sg = new ec2.SecurityGroup(this, 'SgAlb', {
          vpc,
          description: 'AmbonMUD ALB',
          allowAllOutbound: true,
        });
        sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(80), 'HTTP redirect');
        sg.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(443), 'HTTPS WebSocket');
        return sg;
      })(),
    );

    this.albWebTg = new elbv2.ApplicationTargetGroup(this, 'AlbWebTg', {
      vpc,
      port: 8080,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targetType: elbv2.TargetType.IP,
      healthCheck: {
        path: '/healthz',
        port: '8080',
        healthyHttpCodes: '200',
        healthyThresholdCount: 2,
        unhealthyThresholdCount: 3,
      },
    });

    // HTTP → HTTPS redirect
    this.httpListener = this.alb.addListener('HttpListener', {
      port: 80,
      defaultAction: elbv2.ListenerAction.redirect({
        protocol: 'HTTPS',
        port: '443',
        permanent: true,
      }),
    });

    // HTTPS listener (no default action here; dns-stack adds cert + action)
    this.httpsListener = this.alb.addListener('HttpsListener', {
      port: 443,
      protocol: elbv2.ApplicationProtocol.HTTPS,
      // Certificate added in dns-stack via addCertificates()
      certificates: [],
      defaultTargetGroups: [this.albWebTg],
    });
  }
}
