package org.apache.beam.sdk.schemas.logicaltypes;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.schemas.Schema.FieldType;

/**
 * Represents a bit field where each bit is a flag that is either set or unset.
 *
 * <p> This field only supports up to 64 bits and has 64 bits regardless of how many flags
 * are provided. All bits to the left of the last flag will always be zero.
 */
@Experimental(Kind.SCHEMAS)
public final class BitFieldFlagType extends PassThroughLogicalType<Long> {
  public static final String IDENTIFIER = "BitFieldFlag";
  private final Map<String, Integer> flagToBit;

  private BitFieldFlagType(Map<String, Integer> flagToBit) {
    super(IDENTIFIER, FieldType.STRING, "", FieldType.INT64);
    this.flagToBit = flagToBit;
  }

  /**
   * Creates a field with a bit assigned to each flag in {@code flags}.
   *
   * <p> The order of flags should generally not matter, but flags will be in order starting from
   * the rightmost bit. Therefore, given flags A, B, and C in that order, the order in the bit field
   * will be C, B, A.
   */
  public static BitFieldFlagType create(Iterable<String> flags) {
    Map<String, Integer> flagToBit = new HashMap<>();
    int i = 0;
    for (String flag : flags) {
      flagToBit.put(flag, i);
      ++i;
    }

    checkArgument(!flagToBit.isEmpty());
    checkArgument(flagToBit.size() <= 64, "BitFieldFlag only supports up to 64 flags");
    return new BitFieldFlagType(flagToBit);
  }

  /**
   * Convenience method with the same behavior as {@link BitFieldFlagType#create(Iterable)}.
   */
  public static BitFieldFlagType create(String... flags) {
    return create(Arrays.asList(flags));
  }

  /**
   * Returns a bit field where the bit associated with {@code flag} is set. All other bits will
   * be zero.
   */
  public long getWithFlag(String flag) {
    checkArgument(flagToBit.containsKey(flag));
    return 1L << flagToBit.get(flag);
  }

  /**
   * Returns a bit field where each bit with an association in {@code flags} is set. All other bits
   * will be zero.
   */
  public long getWithAllFlags(Iterable<String> flags) {
    long val = 0L;
    for (String flag : flags) {
      val |= getWithFlag(flag);
    }
    return val;
  }

  /**
   * Convenience method with the same behavior as
   * {@link BitFieldFlagType#getWithAllFlags(Iterable)}.
   */
  public long getWithAllFlags(String... flags) {
    return getWithAllFlags(Arrays.asList(flags));
  }
}
