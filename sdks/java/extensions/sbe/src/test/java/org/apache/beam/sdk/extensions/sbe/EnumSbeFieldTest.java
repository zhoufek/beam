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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.apache.beam.sdk.extensions.sbe.SbeField.SbeFieldOptions;
import org.apache.beam.sdk.schemas.Schema.Field;
import org.apache.beam.sdk.schemas.Schema.FieldType;
import org.apache.beam.sdk.schemas.logicaltypes.EnumerationType;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link EnumSbeField}. */
@RunWith(JUnit4.class)
public class EnumSbeFieldTest {
  private static final String NAME = "test-enum-field";
  private static final SbeFieldOptions OPTIONS =
      SbeFieldOptions.builder().setUnsignedOptions(UnsignedOptions.usingSameBitSize()).build();

  @Test
  public void testAsBeamField() {
    ImmutableMap<String, Integer> values = ImmutableMap.of("RED", 1, "GREEN", 10, "YELLOW", 3);

    EnumSbeField field = EnumSbeField.builder().setName(NAME).setValues(values).build();

    Field expected = Field.of(NAME, FieldType.logicalType(EnumerationType.create(values)));
    assertEquals(expected, field.asBeamField(OPTIONS));
  }

  @Test
  public void testAsBeamFieldEmptyValues() {
    assertThrows(
        IllegalStateException.class,
        () -> EnumSbeField.builder().setName(NAME).setValues(ImmutableMap.of()).build());
  }
}
