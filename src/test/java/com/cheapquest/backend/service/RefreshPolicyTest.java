package com.cheapquest.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.dto.firebase.CheapsharkBlock;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.RawgBlock;
import com.cheapquest.backend.fixtures.GameDocumentDtoFixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RefreshPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void constructor_rejectsNullDependencies() {
        assertThatThrownBy(() -> new RefreshPolicy((AppProperties) null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void decide_refreshesBothWhenBothNeverFetched() {
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        GameDocumentDto doc = GameDocumentDtoFixtures.emptyDoc("portal", "Portal");

        RefreshPolicy.RefreshDecision decision = policy.decide(doc);

        assertThat(decision.refreshDeals()).isTrue();
        assertThat(decision.refreshRawg()).isTrue();
        assertThat(decision.isFullRefresh()).isTrue();
        assertThat(decision.nothingToDo()).isFalse();
    }

    @Test
    void decide_firstCallOnBootstrappedDocIsFullRefresh() {
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        GameDocumentDto doc = GameDocumentDtoFixtures.emptyDoc("new-game", "New Game");

        RefreshPolicy.RefreshDecision decision = policy.decide(doc);

        assertThat(decision.refreshDeals())
                .as("bootstrapped doc must refresh deals on first call (no waiting for 24h)")
                .isTrue();
        assertThat(decision.refreshRawg())
                .as("bootstrapped doc must refresh RAWG on first call (no waiting for 180d)")
                .isTrue();
        assertThat(decision.isFullRefresh()).isTrue();
    }

    @Test
    void decide_refreshesOnlyDealsWhenDealsStaleButRawgFresh() {
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        Instant dealsFetched = NOW.minus(Duration.ofHours(25));
        Instant rawgFetched = NOW.minus(Duration.ofDays(30));
        GameDocumentDto doc = docWithFetchedAt(dealsFetched, rawgFetched);

        RefreshPolicy.RefreshDecision decision = policy.decide(doc);

        assertThat(decision.refreshDeals()).isTrue();
        assertThat(decision.refreshRawg()).isFalse();
        assertThat(decision.isFullRefresh()).isFalse();
    }

    @Test
    void decide_refreshesOnlyRawgWhenRawgStaleButDealsFresh() {
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        Instant dealsFetched = NOW.minus(Duration.ofHours(1));
        Instant rawgFetched = NOW.minus(Duration.ofDays(200));
        GameDocumentDto doc = docWithFetchedAt(dealsFetched, rawgFetched);

        RefreshPolicy.RefreshDecision decision = policy.decide(doc);

        assertThat(decision.refreshDeals()).isFalse();
        assertThat(decision.refreshRawg()).isTrue();
        assertThat(decision.isFullRefresh()).isFalse();
    }

    @Test
    void decide_skipsBothWhenBothFresh() {
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        Instant dealsFetched = NOW.minus(Duration.ofHours(1));
        Instant rawgFetched = NOW.minus(Duration.ofDays(30));
        GameDocumentDto doc = docWithFetchedAt(dealsFetched, rawgFetched);

        RefreshPolicy.RefreshDecision decision = policy.decide(doc);

        assertThat(decision.nothingToDo()).isTrue();
        assertThat(decision.isFullRefresh()).isFalse();
    }

    @Test
    void decide_refreshesWhenFetchedAtIsMalformed() {
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        GameDocumentDto doc = docWithRawFetchedAt(NOW.toString(), "not-an-iso-timestamp");

        RefreshPolicy.RefreshDecision decision = policy.decide(doc);

        assertThat(decision.refreshRawg()).isTrue();
    }

    @Test
    void decide_uses24hThresholdBoundaryForDeals() {
        Instant exactlyAtThreshold = NOW.minus(Duration.ofHours(24));
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        GameDocumentDto doc = docWithFetchedAt(exactlyAtThreshold, NOW);

        RefreshPolicy.RefreshDecision decision = policy.decide(doc);

        assertThat(decision.refreshDeals()).isFalse();
    }

    @Test
    void decide_uses180dThresholdBoundaryForRawg() {
        Instant exactlyAtThreshold = NOW.minus(Duration.ofDays(180));
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        GameDocumentDto doc = docWithFetchedAt(NOW, exactlyAtThreshold);

        RefreshPolicy.RefreshDecision decision = policy.decide(doc);

        assertThat(decision.refreshRawg()).isFalse();
    }

    @Test
    void decide_usesAppPropertiesThresholds() {
        AppProperties props = mock(AppProperties.class);
        when(props.refreshDealsMaxAgeHours()).thenReturn(48);
        when(props.refreshRawgMaxAgeDays()).thenReturn(365);
        RefreshPolicy policy = new RefreshPolicy(props, CLOCK);
        Instant dealsFetched = NOW.minus(Duration.ofHours(30));
        Instant rawgFetched = NOW.minus(Duration.ofDays(200));
        GameDocumentDto doc = docWithFetchedAt(dealsFetched, rawgFetched);

        RefreshPolicy.RefreshDecision decision = policy.decide(doc);

        assertThat(decision.refreshDeals()).isFalse();
        assertThat(decision.refreshRawg()).isFalse();
        assertThat(decision.nothingToDo()).isTrue();
    }

    @Test
    void decide_rejectsNullDoc() {
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);

        assertThatThrownBy(() -> policy.decide(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.decide(null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void decide_forceTrueReturnsFullRefreshEvenWhenBothFresh() {
        // Pre-fix: -Dapp.refresh.force=true was implemented by
        // setting 100-year thresholds, which is a hack and breaks
        // when the doc was just hydrated (fetchedAt=now < 100y).
        // Post-fix: the force flag is a separate decision input
        // and unconditionally returns (true, true).
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        GameDocumentDto doc = docWithFetchedAt(NOW, NOW);

        RefreshPolicy.RefreshDecision decision = policy.decide(doc, true);

        assertThat(decision.refreshDeals()).isTrue();
        assertThat(decision.refreshRawg()).isTrue();
        assertThat(decision.isFullRefresh()).isTrue();
    }

    @Test
    void decide_forceFalseFallsBackToCadence() {
        // Sanity check that the new boolean param doesn't change
        // the default cadence behaviour.
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        GameDocumentDto doc = docWithFetchedAt(NOW, NOW);

        RefreshPolicy.RefreshDecision decision = policy.decide(doc, false);

        assertThat(decision.nothingToDo()).isTrue();
    }

    @Test
    void decide_singleArgOverloadDefaultsToForceFalse() {
        RefreshPolicy policy = new RefreshPolicy(Duration.ofHours(24), Duration.ofDays(180), CLOCK);
        GameDocumentDto doc = docWithFetchedAt(NOW, NOW);

        assertThat(policy.decide(doc))
                .isEqualTo(policy.decide(doc, false));
    }

    private static GameDocumentDto docWithFetchedAt(Instant dealsFetchedAt, Instant rawgFetchedAt) {
        return docWithRawFetchedAt(dealsFetchedAt.toString(), rawgFetchedAt.toString());
    }

    private static GameDocumentDto docWithRawFetchedAt(String dealsFetchedAt, String rawgFetchedAt) {
        GameDocumentDto base = GameDocumentDtoFixtures.emptyDoc("portal", "Portal");
        return new GameDocumentDto(
                base.title(),
                base.slug(),
                base.originalLanguage(),
                base.active(),
                base.addedAt(),
                new CheapsharkBlock(
                        base.cheapshark().synced(),
                        base.cheapshark().gameId(),
                        base.cheapshark().cheapestEver(),
                        base.cheapshark().bestDeal(),
                        base.cheapshark().offerCount(),
                        base.cheapshark().deals(),
                        dealsFetchedAt),
                new RawgBlock(
                        base.rawg().synced(),
                        rawgFetchedAt,
                        base.rawg().data()),
                base.locales(),
                base.validationReport());
    }
}
