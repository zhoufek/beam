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

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.extensions.sbe.SbeSchema.IrOptions;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import uk.co.real_logic.sbe.ir.Encoding.Presence;
import uk.co.real_logic.sbe.ir.Ir;
import uk.co.real_logic.sbe.ir.Signal;
import uk.co.real_logic.sbe.ir.Token;

/** Utility for generating {@link SbeField}s from an {@link Ir}. */
@Experimental(Kind.SCHEMAS)
final class IrFieldGenerator {
  // Convenience consumer for when a token should be skipped.
  private static final Consumer<Token> DO_NOTHING = token -> {};

  private IrFieldGenerator() {}

  /**
   * Generates the {@link SbeField}s for a given {@link Ir}.
   *
   * @param ir the intermediate representation to use for generating the fields
   * @param irOptions options for handling ambiguous situations
   * @return all the fields in the IR
   */
  public static ImmutableList<SbeField> generateFields(Ir ir, IrOptions irOptions) {
    ImmutableList.Builder<SbeField> fields = ImmutableList.builder();

    TokenIterator iterator = getIteratorForMessage(ir, irOptions);
    while (iterator.hasNext()) {
      Token token = iterator.next();
      switch (token.signal()) {
        case BEGIN_FIELD:
          fields.add(processPrimitive(iterator));
          break;
        default:
          // TODO(BEAM-12697): Support remaining field types
          break;
      }
    }

    return fields.build();
  }

  /** Helper for getting the tokens for the target message. */
  @SuppressWarnings("nullness") // False positive on already-checked messageId
  private static TokenIterator getIteratorForMessage(Ir ir, IrOptions irOptions) {
    List<Token> messages;

    if (irOptions.messageId() == null && irOptions.messageName() == null) {
      messages =
          ir.messages().stream()
              .collect(
                  collectingAndThen(
                      toList(),
                      lists -> {
                        checkArgument(!lists.isEmpty(), "No messages in IR");
                        checkArgument(
                            lists.size() == 1,
                            "More than one message in IR but no identifier provided.");
                        return lists.get(0);
                      }));
    } else if (irOptions.messageName() != null) {
      String name = irOptions.messageName();
      messages =
          ir.messages().stream()
              .filter(li -> !li.isEmpty() && li.get(0).name().equals(name))
              .collect(
                  collectingAndThen(
                      toList(),
                      lists -> {
                        checkArgument(!lists.isEmpty(), "No messages found with name %s", name);
                        checkArgument(
                            lists.size() == 1, "More than one message found with name %s", name);
                        return lists.get(0);
                      }));
    } else {
      messages = ir.getMessage(irOptions.messageId());
      checkArgument(messages != null, "No message found with id %s", irOptions.messageId());
    }

    return new TokenIterator(messages);
  }

  /** Handles creating a field from the iterator. */
  private static SbeField processPrimitive(TokenIterator iterator) {
    PrimitiveSbeField.Builder primitiveField = PrimitiveSbeField.builder();

    FieldHandler handler =
        FieldHandler.builder()
            .onBeginField(
                token -> {
                  primitiveField.setName(token.name());
                  // At least for primitive fields, the presence is never CONSTANT.
                  primitiveField.setIsRequired(token.encoding().presence() == Presence.REQUIRED);
                })
            .onEncoding(token -> primitiveField.setType(token.encoding().primitiveType()))
            .onEndField(DO_NOTHING)
            .build();
    handler.handle(iterator);

    return primitiveField.build();
  }

  /** Helper class for handling tokens. */
  private static final class FieldHandler {
    private final ImmutableMap<Signal, Consumer<Token>> tokenHandlers;

    private FieldHandler(ImmutableMap<Signal, Consumer<Token>> tokenHandlers) {
      this.tokenHandlers = tokenHandlers;
    }

    static Builder builder() {
      return new Builder();
    }

    void handle(TokenIterator iterator) {
      checkArgument(iterator.current().signal() == Signal.BEGIN_FIELD, "Not beginning of field.");
      checkArgument(iterator.hasNext(), "Field does not have other tokens");

      do {
        Token token = iterator.current();
        handle(token);

        if (token.signal() == Signal.END_FIELD) {
          return;
        }

        iterator.next();
      } while (iterator.hasNext());

      throw new IllegalArgumentException("Never found END_FIELD signal.");
    }

    void handle(Token token) {
      Signal signal = token.signal();
      tokenHandlers.getOrDefault(signal, DO_NOTHING).accept(token);
    }

    /** Builder for {@link FieldHandler}. */
    static final class Builder {
      private final Map<Signal, Consumer<Token>> tokenHandlers;

      Builder() {
        this.tokenHandlers = new EnumMap<>(Signal.class);
      }

      Builder onBeginField(Consumer<Token> tokenHandler) {
        return withTokenHandler(Signal.BEGIN_FIELD, tokenHandler);
      }

      Builder onEncoding(Consumer<Token> tokenHandler) {
        return withTokenHandler(Signal.ENCODING, tokenHandler);
      }

      Builder onEndField(Consumer<Token> tokenHandler) {
        return withTokenHandler(Signal.END_FIELD, tokenHandler);
      }

      private Builder withTokenHandler(Signal signal, Consumer<Token> tokenHandler) {
        checkArgument(
            !tokenHandlers.containsKey(signal),
            "Another handler already registered for: " + signal.name());
        tokenHandlers.put(signal, tokenHandler);
        return this;
      }

      FieldHandler build() {
        return new FieldHandler(ImmutableMap.copyOf(tokenHandlers));
      }
    }
  }

  /** {@link Iterator} over {@link Token}s with support for getting current one. */
  private static final class TokenIterator implements Iterator<Token> {
    private final List<Token> tokens;
    private int idx;

    private TokenIterator(List<Token> tokens) {
      this.tokens = tokens;
      this.idx = -1;
    }

    @Override
    public boolean hasNext() {
      return idx < tokens.size() - 1;
    }

    @Override
    public Token next() {
      ++idx;
      return tokens.get(idx);
    }

    public Token current() {
      return tokens.get(idx);
    }
  }
}
