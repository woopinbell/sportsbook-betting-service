package com.sportsbook.betting.api;

import com.sportsbook.betting.api.PlaceBetRequest.SelectionRequest;
import com.sportsbook.betting.api.PlaceBetRequest.SlipTypeRequest;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.placement.BetPlacementService;
import com.sportsbook.betting.placement.BetQueryService;
import com.sportsbook.betting.placement.PlaceBetCommand;
import com.sportsbook.betting.placement.PlaceBetCommand.SelectionInput;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Odds;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal REST surface for bet placement and lookup (ADR-0004, {@code /internal/v1} prefix;
 * gateway is the public front door). Domain / validation exceptions become RFC 7807 responses via
 * {@link BetExceptionHandler}.
 */
@RestController
@RequestMapping("/internal/v1/bets")
public class BetController {

  private final BetPlacementService placement;
  private final BetQueryService query;

  public BetController(BetPlacementService placement, BetQueryService query) {
    this.placement = placement;
    this.query = query;
  }

  /**
   * Places a slip. Synchronous accept/reject (ADR-0017); the Idempotency-Key header is required.
   */
  @PostMapping
  public ResponseEntity<BetResponse> place(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody PlaceBetRequest request) {
    Bet bet = placement.place(toCommand(request, idempotencyKey));
    return ResponseEntity.created(URI.create("/internal/v1/bets/" + bet.betId()))
        .body(BetResponse.from(bet));
  }

  @GetMapping("/{betId}")
  public BetResponse get(@PathVariable UUID betId) {
    return BetResponse.from(query.byId(betId));
  }

  /** A user's bets, newest first, keyset-paginated by betId (ADR-0004 cursor pagination). */
  @GetMapping
  public CursorPage<BetResponse> list(
      @RequestParam UUID userId,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(required = false) Integer limit) {
    CursorPage<Bet> page = query.page(userId, cursor, limit);
    List<BetResponse> items = page.items().stream().map(BetResponse::from).toList();
    return new CursorPage<>(items, page.nextCursor(), page.hasMore());
  }

  private static PlaceBetCommand toCommand(PlaceBetRequest request, String idempotencyKey) {
    List<SelectionInput> selections =
        request.selections().stream().map(BetController::toSelection).toList();
    return new PlaceBetCommand(
        request.userId(),
        toSlipType(request.slipType()),
        selections,
        request.stake(),
        IdempotencyKey.of(idempotencyKey));
  }

  private static SelectionInput toSelection(SelectionRequest selection) {
    return new SelectionInput(
        selection.eventId(),
        selection.marketId(),
        selection.selectionId(),
        Odds.ofDecimal(selection.odds()));
  }

  private static BetSlipType toSlipType(SlipTypeRequest slipType) {
    return switch (slipType.type().toUpperCase(Locale.ROOT)) {
      case "SINGLE" -> new BetSlipType.Single();
      case "MULTIPLE" -> new BetSlipType.Multiple();
      case "SYSTEM" ->
          new BetSlipType.System(
              required(slipType.minWins(), "minWins"),
              required(slipType.totalSelections(), "totalSelections"));
      default -> throw new IllegalArgumentException("Unknown slip type: " + slipType.type());
    };
  }

  private static int required(Integer value, String name) {
    if (value == null) {
      throw new IllegalArgumentException("SYSTEM slip requires " + name);
    }
    return value;
  }
}
