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

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Experimental.Kind;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.logicaltypes.EnumerationType;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;

/** Represents an SBE enumeration type. */
@Experimental(Kind.SCHEMAS)
@AutoValue
abstract class EnumSbeField implements SbeField {

  @Override
  public abstract String name();

  @Override
  public Boolean isRequired() {
    return true;
  }

  public abstract ImmutableMap<String, Integer> values();

  @Override
  public Field asBeamField(SbeFieldOptions options) {
    return Field.of(name(), FieldType.logicalType(EnumerationType.create(values())));
  }

  public static Builder builder() {
    return new AutoValue_EnumSbeField.Builder();
  }

  /** Builder for {@link EnumSbeField}. */
  @AutoValue.Builder
  abstract static class Builder {

    public abstract Builder setName(String value);

    public abstract Builder setValues(ImmutableMap<String, Integer> value);

    public abstract EnumSbeField autoBuild();

    public EnumSbeField build() {
      EnumSbeField field = autoBuild();
      checkState(!field.values().isEmpty(), "Must have at least one enum option");
      return field;
    }
  }
}
