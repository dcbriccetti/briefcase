/*
 * Copyright (C) 2018 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.briefcase.export;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ExportToCsvNestedRepeatsTest {
  private ExportToCsvScenario scenario;

  @Before
  public void setUp() {
    scenario = ExportToCsvScenario.setUp("nested-repeats");
  }

  @After
  public void tearDown() {
    scenario.tearDown();
  }

  @Test
  public void exports_cascading_groups_to_different_files() {
    scenario.runOldExport();
    scenario.runNewExport();
    scenario.assertSameContent();
    scenario.assertSameContentRepeats("g1", "g2", "g3");
  }

}