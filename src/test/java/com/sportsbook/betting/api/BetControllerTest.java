package com.sportsbook.betting.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.error.BetNotFoundException;
import com.sportsbook.betting.error.DuplicateBetException;
import com.sportsbook.betting.error.InsufficientBalanceException;
import com.sportsbook.betting.error.MarketClosedException;
import com.sportsbook.betting.error.OddsDriftException;
import com.sportsbook.betting.error.RiskLimitException;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.placement.BetPlacementService;
import com.sportsbook.betting.placement.BetQueryService;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer + RFC 7807 advice coverage (@WebMvcTest, services mocked). */
@WebMvcTest(BetController.class)
class BetControllerTest {

  private static final UUID USER = UuidV7.generate();

  @Autowired MockMvc mvc;
  @MockBean BetPlacementService placement;
  @MockBean BetQueryService query;

  private static String singleRequest() {
    return "{"
        + "\"userId\":\""
        + USER
        + "\","
        + "\"slipType\":{\"type\":\"SINGLE\"},"
        + "\"selections\":[{\"eventId\":\""
        + UuidV7.generate()
        + "\",\"marketId\":\""
        + UuidV7.generate()
        + "\",\"selectionId\":\""
        + UuidV7.generate()
        + "\",\"odds\":2.0}],"
        + "\"stake\":{\"amount\":10000,\"currency\":\"KRW\"}"
        + "}";
  }

  private static Bet acceptedBet() {
    Bet bet =
        Bet.pending(
            UuidV7.generate(),
            USER,
            "B-2026-05-29-WEB00001",
            new BetSlipType.Single(),
            Money.krw(10_000),
            Money.krw(20_000),
            IdempotencyKey.of("idem-web"),
            List.of(
                BetLeg.create(
                    UuidV7.generate(),
                    UuidV7.generate(),
                    UuidV7.generate(),
                    Odds.ofDecimal("2.0000"))),
            Instant.parse("2026-05-29T07:00:00Z"));
    bet.accept(Instant.parse("2026-05-29T07:00:01Z"));
    return bet;
  }

  @Test
  @DisplayName("POST accepts -> 201 with Location and ACCEPTED body")
  void placeAccepted() throws Exception {
    when(placement.place(any())).thenReturn(acceptedBet());

    mvc.perform(
            post("/internal/v1/bets")
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest()))
        .andExpect(status().isCreated())
        .andExpect(
            header().string("Location", org.hamcrest.Matchers.startsWith("/internal/v1/bets/")))
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.betReference").value("B-2026-05-29-WEB00001"))
        .andExpect(jsonPath("$.maxPayout.amount").value(20000));
  }

  @Test
  @DisplayName("POST without Idempotency-Key -> 400 VALIDATION_FAILED")
  void missingIdempotencyKey() throws Exception {
    mvc.perform(
            post("/internal/v1/bets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("POST with empty selections -> 400 (bean validation)")
  void emptySelections() throws Exception {
    String body =
        "{\"userId\":\""
            + USER
            + "\",\"slipType\":{\"type\":\"SINGLE\"},\"selections\":[],"
            + "\"stake\":{\"amount\":10000,\"currency\":\"KRW\"}}";
    mvc.perform(
            post("/internal/v1/bets")
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("risk decline -> 403 LIMIT_EXCEEDED")
  void riskDecline() throws Exception {
    when(placement.place(any())).thenThrow(new RiskLimitException("daily limit"));
    expectProblem(403, "LIMIT_EXCEEDED");
  }

  @Test
  @DisplayName("insufficient balance -> 409 INSUFFICIENT_BALANCE")
  void insufficientBalance() throws Exception {
    when(placement.place(any())).thenThrow(new InsufficientBalanceException("too low"));
    expectProblem(409, "INSUFFICIENT_BALANCE");
  }

  @Test
  @DisplayName("odds drift -> 409 ODDS_DRIFT")
  void oddsDrift() throws Exception {
    when(placement.place(any())).thenThrow(new OddsDriftException("drifted"));
    expectProblem(409, "ODDS_DRIFT");
  }

  @Test
  @DisplayName("market closed -> 422 EVENT_CLOSED")
  void marketClosed() throws Exception {
    when(placement.place(any())).thenThrow(new MarketClosedException("suspended"));
    expectProblem(422, "EVENT_CLOSED");
  }

  @Test
  @DisplayName("duplicate in-flight -> 409 DUPLICATE_BET")
  void duplicate() throws Exception {
    when(placement.place(any())).thenThrow(new DuplicateBetException("in progress"));
    expectProblem(409, "DUPLICATE_BET");
  }

  @Test
  @DisplayName("GET by id not found -> 404 BET_NOT_FOUND")
  void notFound() throws Exception {
    UUID betId = UuidV7.generate();
    when(query.byId(betId)).thenThrow(new BetNotFoundException("nope"));

    mvc.perform(get("/internal/v1/bets/{id}", betId))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value("BET_NOT_FOUND"));
  }

  @Test
  @DisplayName("GET by id found -> 200 with body")
  void getById() throws Exception {
    Bet bet = acceptedBet();
    when(query.byId(bet.betId())).thenReturn(bet);

    mvc.perform(get("/internal/v1/bets/{id}", bet.betId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.selections", org.hamcrest.Matchers.hasSize(1)));
  }

  @Test
  @DisplayName("GET list -> 200 cursor page")
  void listPage() throws Exception {
    when(query.page(any(), any(), any()))
        .thenReturn(new CursorPage<>(List.of(acceptedBet()), "cursor-123", true));

    mvc.perform(get("/internal/v1/bets").param("userId", USER.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$.nextCursor").value("cursor-123"))
        .andExpect(jsonPath("$.hasMore").value(true));
  }

  private void expectProblem(int statusCode, String errorCode) throws Exception {
    mvc.perform(
            post("/internal/v1/bets")
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest()))
        .andExpect(status().is(statusCode))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value(errorCode));
  }
}
