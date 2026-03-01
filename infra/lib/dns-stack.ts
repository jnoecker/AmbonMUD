import { Stack, StackProps } from 'aws-cdk-lib';
import * as acm from 'aws-cdk-lib/aws-certificatemanager';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as route53 from 'aws-cdk-lib/aws-route53';
import * as targets from 'aws-cdk-lib/aws-route53-targets';
import { Construct } from 'constructs';
import { LbStack } from './lb-stack';

export interface DnsStackProps extends StackProps {
  /**
   * The apex domain for the game (e.g. "example.com").
   * Provide via --context domain=example.com at deploy time.
   * If omitted, DNS/TLS setup is skipped (useful for hobby deployments
   * that use the raw NLB/ALB DNS names).
   */
  readonly domain?: string;
  readonly lbStack: LbStack;
}

export class DnsStack extends Stack {
  readonly certificate?: acm.Certificate;
  readonly hostedZone?: route53.IHostedZone;

  constructor(scope: Construct, id: string, props: DnsStackProps) {
    super(scope, id, props);

    const { domain, lbStack } = props;

    if (!domain) {
      // No domain configured — skip DNS/TLS. Players use NLB/ALB DNS names directly.
      return;
    }

    // -------------------------------------------------------------------------
    // Route 53 hosted zone (must already exist in the AWS account)
    // -------------------------------------------------------------------------
    this.hostedZone = route53.HostedZone.fromLookup(this, 'HostedZone', {
      domainName: domain,
    });

    // -------------------------------------------------------------------------
    // ACM certificate (DNS validated, covers apex + wildcard)
    // -------------------------------------------------------------------------
    this.certificate = new acm.Certificate(this, 'Certificate', {
      domainName: domain,
      subjectAlternativeNames: [`*.${domain}`],
      validation: acm.CertificateValidation.fromDns(this.hostedZone),
    });

    // Attach certificate to ALB HTTPS listener
    lbStack.httpsListener.addCertificates('GameCert', [
      elbv2.ListenerCertificate.fromArn(this.certificate.certificateArn),
    ]);

    // -------------------------------------------------------------------------
    // Route 53 A alias records
    // -------------------------------------------------------------------------

    // play.<domain> → ALB (WebSocket clients)
    new route53.ARecord(this, 'PlayRecord', {
      zone: this.hostedZone,
      recordName: `play.${domain}`,
      target: route53.RecordTarget.fromAlias(
        new targets.LoadBalancerTarget(lbStack.alb),
      ),
    });

    // telnet.<domain> → NLB (telnet clients)
    // Note: NLB DNS names are stable but not aliases in the traditional sense.
    // Clients connecting via raw TCP telnet can use the NLB DNS directly or
    // a CNAME to the NLB DNS name (Route 53 alias for NLB is also supported).
    new route53.ARecord(this, 'TelnetRecord', {
      zone: this.hostedZone,
      recordName: `telnet.${domain}`,
      target: route53.RecordTarget.fromAlias(
        new targets.LoadBalancerTarget(lbStack.nlb),
      ),
    });
  }
}
