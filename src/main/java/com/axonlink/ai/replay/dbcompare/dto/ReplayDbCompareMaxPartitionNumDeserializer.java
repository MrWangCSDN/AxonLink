package com.axonlink.ai.replay.dbcompare.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Prevent Jackson from truncating fractions or coercing strings into maximum HASH partition counts. */
public class ReplayDbCompareMaxPartitionNumDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return context.reportInputMismatch(Integer.class, "最大HASH分区数必须为 1 到 256 的整数");
        }
        var number = parser.getBigIntegerValue();
        if (number.signum() <= 0 || number.compareTo(java.math.BigInteger.valueOf(256)) > 0) {
            return context.reportInputMismatch(Integer.class, "最大HASH分区数必须为 1 到 256 的整数");
        }
        return number.intValue();
    }
}
