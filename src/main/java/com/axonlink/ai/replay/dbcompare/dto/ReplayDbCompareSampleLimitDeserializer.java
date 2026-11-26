package com.axonlink.ai.replay.dbcompare.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Prevent Jackson from truncating fractions or coercing strings into sample limits. */
public class ReplayDbCompareSampleLimitDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            try {
                int number = parser.getBigIntegerValue().intValueExact();
                if (ReplayDbCompareSampleLimit.isValid(number)) return number;
            } catch (ArithmeticException ignored) {
                // Out-of-range integers use the same domain-specific error.
            }
        }
        return context.reportInputMismatch(Integer.class, ReplayDbCompareSampleLimit.RANGE_ERROR);
    }
}
