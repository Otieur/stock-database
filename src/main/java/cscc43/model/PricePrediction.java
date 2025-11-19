package cscc43.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a future predicted closing price.
 */
public record PricePrediction(LocalDate day, BigDecimal predictedClose) {
}
