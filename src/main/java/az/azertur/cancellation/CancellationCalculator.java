package az.azertur.cancellation;

import az.azertur.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.Instant;
import java.util.*;

public final class CancellationCalculator {
  private CancellationCalculator() {}
  public record Tier(@NotNull Instant fromInclusive,
      @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal retainedPercent,
      @Min(0) long fixedFeeMinor,
      @NotNull @Pattern(regexp="PAID_AMOUNT|SERVICE_PRICE") String feeBasis) {}
  public record Result(long paidMinor,long retainedMinor,long refundMinor,Instant tierFrom) {}
  public static Result calculate(long paidMinor,long servicePriceMinor,Instant requestedAt,List<Tier> tiers) {
    if(paidMinor<0 || servicePriceMinor<paidMinor || requestedAt==null || tiers==null || tiers.isEmpty())
      throw DomainException.bad("INVALID_CANCELLATION_RULE");
    var seen=new HashSet<Instant>();
    for(var t:tiers) {
      if(t==null || t.fromInclusive()==null || t.retainedPercent()==null
          || t.retainedPercent().signum()<0 || t.retainedPercent().compareTo(BigDecimal.valueOf(100))>0
          || t.fixedFeeMinor()<0 || !List.of("PAID_AMOUNT","SERVICE_PRICE").contains(t.feeBasis()==null?"":t.feeBasis()) || !seen.add(t.fromInclusive()))
        throw DomainException.bad("INVALID_CANCELLATION_RULE");
    }
    var rule=tiers.stream().filter(t -> !requestedAt.isBefore(t.fromInclusive()))
        .max(Comparator.comparing(Tier::fromInclusive))
        .orElseThrow(() -> DomainException.conflict("CANCELLATION_RULE_NOT_COVERED"));
    var fee=BigDecimal.valueOf(rule.feeBasis().equals("SERVICE_PRICE")?servicePriceMinor:paidMinor).multiply(rule.retainedPercent()).divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP)
        .add(BigDecimal.valueOf(rule.fixedFeeMinor())).min(BigDecimal.valueOf(paidMinor)).longValueExact();
    return new Result(paidMinor,fee,paidMinor-fee,rule.fromInclusive());
  }
}
