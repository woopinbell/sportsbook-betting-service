package com.sportsbook.betting.outbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

/**
 * Binary-encodes / decodes Avro records for the outbox payload column. V1 publishes without a
 * Schema Registry (ADR-0014); each consumer pins the same shared-protocol generated classes so the
 * schema id is implicit. This helper exists so the encode boilerplate does not leak into the
 * service layer; the decode side is currently used only by tests asserting on outbox payload
 * content.
 */
public final class AvroSerializer {

  public static <T extends SpecificRecord> byte[] serialize(T record) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      DatumWriter<SpecificRecord> writer = new SpecificDatumWriter<>(record.getSchema());
      BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
      writer.write(record, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to serialize Avro record " + record.getSchema().getFullName(), e);
    }
  }

  public static <T extends SpecificRecord> T deserialize(byte[] payload, Class<T> type) {
    try {
      DatumReader<T> reader = new SpecificDatumReader<>(type);
      return reader.read(null, DecoderFactory.get().binaryDecoder(payload, null));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to deserialize Avro record " + type.getName(), e);
    }
  }

  private AvroSerializer() {
    // Utility holder.
  }
}
