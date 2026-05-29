package com.sportsbook.betting.domain;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;

/**
 * JPA-side mirror of {@link Money} (ADR-0003). Hibernate 6 cannot bind a record from another module
 * as an {@code @Embeddable} directly, so this wrapper gives the persistence layer a class with a
 * no-arg constructor and reflective field access. Domain code keeps using {@link Money} via {@link
 * #toMoney()} and {@link #of(Money)}.
 *
 * <p>Equality is value-based on both fields, matching {@link Money}. Mirrors the same type in
 * wallet-service; each service owns its persistence types rather than sharing a JPA model.
 */
@Embeddable
public class EmbeddedMoney {

  @Column(nullable = false)
  private long amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 3)
  private Currency currency;

  protected EmbeddedMoney() {
    // Required by JPA.
  }

  public EmbeddedMoney(long amount, Currency currency) {
    this.currency = Objects.requireNonNull(currency, "currency");
    this.amount = amount;
  }

  public static EmbeddedMoney of(Money money) {
    Objects.requireNonNull(money, "money");
    return new EmbeddedMoney(money.amount(), money.currency());
  }

  public Money toMoney() {
    return new Money(amount, currency);
  }

  public long amount() {
    return amount;
  }

  public Currency currency() {
    return currency;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EmbeddedMoney other)) {
      return false;
    }
    return amount == other.amount && currency == other.currency;
  }

  @Override
  public int hashCode() {
    return Objects.hash(amount, currency);
  }

  @Override
  public String toString() {
    return amount + " " + currency;
  }
}
