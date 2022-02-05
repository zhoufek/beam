/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.sbe;

import static java.util.stream.Collectors.toCollection;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.io.payloads.PayloadSerializerProvider;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.real_logic.sbe.ir.Ir;
import uk.co.real_logic.sbe.ir.Token;

/**
 * Represents an SBE schema that can be translated to a Beam {@link Schema} and {@link
 * PayloadSerializerProvider}.
 *
 * <p>The schema represents a single SBE message. If the XML schema contains more than one message,
 * then a new instance must be created for each message that the pipeline will work with.
 *
 * <p>The currently supported ways of generating a schema are:
 *
 * <ul>
 *   <li>Through an intermediate representation ({@link Ir}).
 * </ul>
 *
 * <h3>Intermediate Representation</h3>
 *
 * <p>An {@link Ir} allows for a reflection-less way of getting a very accurate representation of
 * the SBE schema, since it is a tokenized form of the original XML schema. To help deal with some
 * ambiguities, such as which message to base the schema around, passing {@link IrOptions} is
 * required. See the Javadoc for the options for more details.
 *
 * <p>At this time, we cannot support serialization to an SBE message through IR. As a result, the
 * {@code byte[]} output from the {@link PayloadSerializerProvider} serializer will be from a JSON
 * representation of the message, not an SBE-serialized message. Downstream systems will need to
 * account for this.
 */
@Experimental(Kind.SCHEMAS)
public final class SbeSchema implements Serializable {
  private static final long serialVersionUID = 1L;

  @Nullable private final SerializableIr ir;
  @Nullable private final IrOptions irOptions;

  private SbeSchema(@Nullable SerializableIr ir, @Nullable IrOptions irOptions) {
    this.ir = ir;
    this.irOptions = irOptions;
  }

  /**
   * Creates a new {@link SbeSchema} from the given intermediate representation.
   *
   * <p>This makes no guarantees about the state of the returned instance. That is, it may or may
   * not have the generated SBE schema representation, and it may or may not have translated the SBE
   * schema into a Beam schema.
   *
   * @param ir the intermediate representation of the SBE schema. Modifications to the passed-in
   *     value will not be reflected in the returned instance.
   * @param irOptions options for configuring how to deal with cases where the desired behavior is
   *     ambiguous.
   * @return a new {@link SbeSchema} instance
   */
  public static SbeSchema fromIr(Ir ir, IrOptions irOptions) {
    validateIrOptions(ir, irOptions);

    Ir copy =
        new Ir(
            ir.packageName(),
            ir.namespaceName(),
            ir.id(),
            ir.version(),
            ir.description(),
            ir.semanticVersion(),
            ir.byteOrder(),
            ImmutableList.copyOf(ir.headerStructure().tokens()));

    return new SbeSchema(SerializableIr.fromIr(copy), irOptions);
  }

  /** Handles validation of {@link IrOptions} in relation to an {@link Ir}. */
  private static void validateIrOptions(Ir ir, IrOptions irOptions) {
    boolean singleMessageSchema = irOptions.messageId() == -1 && irOptions.messageName().equals("");
    checkArgument(
        !singleMessageSchema || ir.messages().size() == 1,
        "irOptions assumes single message schema, but there are %s messages",
        ir.messages().size());

    if (irOptions.messageId() > -1) {
      checkArgument(
          ir.getMessage(irOptions.messageId()) != null,
          "There is no message with the id %s",
          irOptions.messageId());
    } else {
      Collection<List<Token>> matchingMessages =
          ir.messages().stream()
              .filter(li -> !li.isEmpty() && li.get(0).name().equals(irOptions.messageName()))
              .collect(toCollection(ArrayList::new));
      checkArgument(
          !matchingMessages.isEmpty(), "No message with name %s", irOptions.messageName());
      checkArgument(
          matchingMessages.size() == 1,
          "More than one message has the name %s",
          irOptions.messageName());
    }
  }

  /**
   * Options for configuring schema generation from an {@link Ir}.
   *
   * <p>The default options make the following assumptions:
   *
   * <ul>
   *   <p>There is only message in the XML schema. In order to override this, either {@link
   *   IrOptions#messageId()} or {@link IrOptions#messageName()} must be set, but not both.
   * </ul>
   */
  @AutoValue
  public abstract static class IrOptions implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final IrOptions DEFAULT = IrOptions.builder().build();

    public abstract int messageId();

    public abstract String messageName();

    public static Builder builder() {
      return new AutoValue_SbeSchema_IrOptions.Builder().setMessageId(-1).setMessageName("");
    }

    public abstract Builder toBuilder();

    /** Builder for {@link IrOptions}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setMessageId(int value);

      public abstract Builder setMessageName(String value);

      abstract IrOptions autoBuild();

      public IrOptions build() {
        IrOptions opts = autoBuild();

        boolean messageIdentifierValid =
            (opts.messageId() > -1 ^ !opts.messageName().equals(""))
                || (opts.messageId() == -1 && opts.messageName().equals(""));
        checkState(messageIdentifierValid, "At most one of messageId or messageName can be set");

        return opts;
      }
    }
  }
}
