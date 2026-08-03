package dev.linhvu.news_aggregator.infra;

import java.util.List;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.certificatemanager.CertificateValidation;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.route53.IHostedZone;
import software.amazon.awscdk.services.route53.PublicHostedZone;
import software.constructs.Construct;

public class DnsStack extends Stack {

	private final IHostedZone hostedZone;
	private final ICertificate certificate;

	public DnsStack(final Construct scope, final String id, final EnvConfig cfg) {
		super(scope, id, StackProps.builder().env(cfg.awsEnvironment()).build());

		PublicHostedZone zone = PublicHostedZone.Builder.create(this, "HostedZone")
				.zoneName(cfg.zoneName())
				.build();
		zone.applyRemovalPolicy(cfg.removalPolicy());
		this.hostedZone = zone;
		
		this.certificate = Certificate.Builder.create(this, "Certificate")
				.domainName(cfg.appDomain())
				.validation(CertificateValidation.fromDns(zone))
				.build();

		CfnOutput.Builder.create(this, "NameServers")
				.description("Thêm 4 giá trị này thành NS record cho "
						+ cfg.zoneName()
						+ " ở zone apex linhvu.dev (management account 784563198762)")
				.value(Fn.join(" , ", zone.getHostedZoneNameServers() != null
						? zone.getHostedZoneNameServers() : List.of()))
				.build();
	}

	public IHostedZone getHostedZone() {
		return hostedZone;
	}

	public ICertificate getCertificate() {
		return certificate;
	}
}
