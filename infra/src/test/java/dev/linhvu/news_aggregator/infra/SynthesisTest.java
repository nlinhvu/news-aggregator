package dev.linhvu.news_aggregator.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.cxapi.CloudAssembly;
import software.amazon.awscdk.cxapi.NestedCloudAssemblyArtifact;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SynthesisTest {

	@Test
	void ca_ba_stage_synth_duoc_khong_can_credential() {
		App app = new App();
		new AppStage(app, EnvConfig.DEV);
		new AppStage(app, EnvConfig.QA);
		new AppStage(app, EnvConfig.PROD);

		CloudAssembly assembly = app.synth();

		assertEquals(
				Set.of("Dev", "Qa", "Prod"),
				assembly.getNestedAssemblies().stream()
						.map(NestedCloudAssemblyArtifact::getDisplayName)
						.collect(Collectors.toSet()));
	}
}