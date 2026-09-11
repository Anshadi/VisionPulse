package com.visionpulse.idgen;

import com.visionpulse.model.ParsedIdDto;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class IdPacker {

    private static final long CUSTOM_EPOCH = 1704067200000L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;

    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_MASK = ~(-1L << WORKER_ID_BITS);
    private static final long DATACENTER_ID_MASK = ~(-1L << DATACENTER_ID_BITS);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    public static ParsedIdDto unpack(long id) {
        long sequence = id & SEQUENCE_MASK;
        int workerId = (int) ((id >> SEQUENCE_BITS) & WORKER_ID_MASK);
        int datacenterId = (int) ((id >> (SEQUENCE_BITS + WORKER_ID_BITS)) & DATACENTER_ID_MASK);
        long timestampDelta = id >> (SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS);
        long epochTimestamp = timestampDelta + CUSTOM_EPOCH;

        String formatted = FORMATTER.format(Instant.ofEpochMilli(epochTimestamp));
        String binaryString = String.format("%64s", Long.toBinaryString(id)).replace(' ', '0');

        return ParsedIdDto.builder()
                .rawId(id)
                .timestamp(epochTimestamp)
                .formattedTimestamp(formatted)
                .datacenterId(datacenterId)
                .workerId(workerId)
                .sequence(sequence)
                .binaryString(binaryString)
                .build();
    }
}
