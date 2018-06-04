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

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.util.stream.Collectors.toConcurrentMap;
import static org.opendatakit.briefcase.export.CsvMapper.getMainHeader;
import static org.opendatakit.briefcase.export.CsvMapper.getMainSubmissionLines;
import static org.opendatakit.briefcase.export.CsvMapper.getRepeatCsvLine;
import static org.opendatakit.briefcase.export.CsvMapper.getRepeatHeader;
import static org.opendatakit.briefcase.export.ExportOutcome.ALL_EXPORTED;
import static org.opendatakit.briefcase.export.ExportOutcome.ALL_SKIPPED;
import static org.opendatakit.briefcase.export.ExportOutcome.SOME_SKIPPED;
import static org.opendatakit.briefcase.reused.UncheckedFiles.append;
import static org.opendatakit.briefcase.reused.UncheckedFiles.createDirectories;
import static org.opendatakit.briefcase.reused.UncheckedFiles.newOutputStreamWriter;
import static org.opendatakit.briefcase.reused.UncheckedFiles.walk;
import static org.opendatakit.briefcase.util.StringUtils.stripIllegalChars;

import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import org.bushe.swing.event.EventBus;
import org.kxml2.kdom.Document;
import org.opendatakit.briefcase.model.BriefcaseFormDefinition;
import org.opendatakit.briefcase.reused.BriefcaseException;
import org.opendatakit.briefcase.reused.UncheckedFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExportToCsv {
  private static final Logger log = LoggerFactory.getLogger(ExportToCsv.class);

  /**
   * Export a form's submissions into some CSV files.
   * <p>
   * If the form has repeat groups, each repeat group will be exported into a separate CSV file.
   *
   * @param formDef       the {@link BriefcaseFormDefinition} form definition of the form to be exported
   * @param configuration the {@link ExportConfiguration} export configuration
   * @param exportMedia   a {@link Boolean} indicating if media files attached to each submission must be also exported
   * @return an {@link ExportOutcome} with the export operation's outcome
   * @see ExportConfiguration
   */
  public static ExportOutcome export(FormDefinition formDef, ExportConfiguration configuration, boolean exportMedia) {
    long start = System.nanoTime();
    // Create an export tracker object with the total number of submissions we have to export
    long submissionCount = walk(formDef.getFormDir().resolve("instances"))
        .filter(UncheckedFiles::isInstanceDir)
        .count();
    ExportProcessTracker exportTracker = new ExportProcessTracker(formDef, submissionCount);
    exportTracker.start();

    // Compute and create the export directory
    Path exportDir = configuration.getExportDir().orElseThrow(() -> new BriefcaseException("No export dir defined"));
    createDirectories(exportDir);

    // Get the repeat group models ready for later use
    List<Model> repeatGroups = formDef.getModel().getRepeatableFields();

    // Get a Map with all the files we need to produce:
    // - One per repeat group
    // - One for the main CSV file
    Map<Model, OutputStreamWriter> files = repeatGroups.stream().collect(toConcurrentMap(
        group -> group,
        group -> getOutputStreamWriter(
            exportDir.resolve(stripIllegalChars(formDef.getFormName()) + "-" + group.getName() + ".csv"),
            configuration.getOverwriteExistingFiles().orElse(true),
            getRepeatHeader(group)
        )
    ));
    OutputStreamWriter mainFile = getOutputStreamWriter(
        exportDir.resolve(stripIllegalChars(formDef.getFormName()) + ".csv"),
        configuration.getOverwriteExistingFiles().orElse(true),
        getMainHeader(formDef.getModel(), formDef.isFileEncryptedForm())
    );
    files.put(formDef.getModel(), mainFile);
    Map<Path, Document> docsByPath = new WeakHashMap<>();

    SubmissionParser
        .getOrderedListOfSubmissionFiles(formDef.getFormDir(), configuration.getDateRange(), docsByPath)
        .stream()
        .map(path -> SubmissionParser.parseSubmission(path, formDef.isFileEncryptedForm(), configuration.getPrivateKey(), docsByPath))
        .filter(Optional::isPresent)
        .map(Optional::get)
        // While we iterate over each submission, take a peek, and
        // write lines on each repeat group CSV file
        .peek(submission -> repeatGroups.forEach(groupModel -> append(getRepeatCsvLine(
            groupModel,
            submission.getElements(groupModel.fqn()),
            exportMedia,
            configuration.getExportMediaPath(),
            submission.getInstanceId(),
            submission.getWorkingDir()
        ), files.get(groupModel))))
        // Write lines in the main CSV file
        .forEach(submission -> {
          // Increment the export count and maybe report progress
          exportTracker.incAndReport();
          append(getMainSubmissionLines(
              submission,
              formDef.getModel(),
              formDef.isFileEncryptedForm(),
              exportMedia,
              configuration.getExportMediaPath()
          ), mainFile);
        });

    // Flush and close output streams
    files.values().forEach(UncheckedFiles::close);

    exportTracker.end();

    ExportOutcome exportOutcome = exportTracker.computeOutcome();
    if (exportOutcome == ALL_EXPORTED)
      EventBus.publish(ExportEvent.successForm(formDef, (int) exportTracker.total));

    if (exportOutcome == SOME_SKIPPED)
      EventBus.publish(ExportEvent.partialSuccessForm(formDef, (int) exportTracker.exported, (int) exportTracker.total));

    if (exportOutcome == ALL_SKIPPED)
      EventBus.publish(ExportEvent.failure(formDef, "All submissions have been skipped"));

    long end = System.nanoTime();
    LocalTime duration = LocalTime.ofNanoOfDay(end - start);
    log.info("Exported in {}", duration.format(DateTimeFormatter.ISO_TIME));
    return exportOutcome;
  }

  private static OutputStreamWriter getOutputStreamWriter(Path outputFile, Boolean overwrite, String header) {
    if (Files.exists(outputFile) && !overwrite)
      return newOutputStreamWriter(outputFile, APPEND);
    // If we are not appending, open the file, truncate it if it already exists, and write the header
    OutputStreamWriter osw = newOutputStreamWriter(outputFile, CREATE, TRUNCATE_EXISTING);
    append(header, osw);
    return osw;
  }

}
