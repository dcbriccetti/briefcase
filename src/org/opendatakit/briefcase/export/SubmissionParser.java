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

import static java.util.Comparator.comparingLong;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.codec.binary.Base64.decodeBase64;
import static org.opendatakit.briefcase.export.CipherFactory.signatureDecrypter;
import static org.opendatakit.briefcase.reused.UncheckedFiles.createTempDirectory;
import static org.opendatakit.briefcase.reused.UncheckedFiles.list;
import static org.opendatakit.briefcase.reused.UncheckedFiles.stripFileExtension;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.IllegalBlockSizeException;
import org.kxml2.io.KXmlParser;
import org.kxml2.kdom.Document;
import org.opendatakit.briefcase.model.CryptoException;
import org.opendatakit.briefcase.reused.OptionalProduct;
import org.opendatakit.briefcase.reused.Pair;
import org.opendatakit.briefcase.reused.UncheckedFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * This class holds the main submission parsing code.
 */
class SubmissionParser {
  private static final Logger log = LoggerFactory.getLogger(SubmissionParser.class);

  /**
   * Returns an sorted {@link List} of {@link Path} instances pointing to all the
   * submissions of a form that belong to the given {@link DateRange}.
   * <p>
   * Each file gets briefly parsed to obtain their submission date and use it as
   * the sorting criteria and for filtering.
   *
   * @param formDir   the {@link Path} directory of the form
   * @param dateRange a {@link DateRange} to filter submissions that are contained in it
   */
  static List<Path> getOrderedListOfSubmissionFiles(Path formDir, DateRange dateRange, Map<Path, Document> docsByPath) {
    Path instancesDir = formDir.resolve("instances");
    if (!Files.exists(instancesDir) || !Files.isReadable(instancesDir))
      return Collections.emptyList();
    return list(instancesDir)
        .filter(UncheckedFiles::isInstanceDir)
        .map(instanceDir -> instanceDir.resolve("submission.xml"))
        // Pair each path with the parsed submission date
        .map(submissionPath -> Pair.of(
            submissionPath,
            readSubmissionDate(submissionPath, docsByPath).orElse(OffsetDateTime.MIN)
        ))
        // Filter out submissions outside the given date range
        .filter(pair -> dateRange.contains(pair.getRight()))
        // Sort them and return a list of paths
        .sorted(comparingLong(pair -> pair.getRight().toInstant().toEpochMilli()))
        .map(Pair::getLeft)
        .collect(toList());
  }

  /**
   * Returns a parsed {@link Submission}, wrapped inside an {@link Optional} instance if
   * it meets some criteria:
   * <ul>
   * <li>The given {@link Path} points to a parseable submission file</li>
   * <li>If the form is encrypted, the submission can be decrypted</li>
   * </ul>
   * Returns an {@link Optional#empty()} otherwise.
   *
   * @param path        the {@link Path} to the submission file
   * @param isEncrypted a {@link Boolean} indicating whether the form is encrypted or not.
   * @param privateKey  the {@link PrivateKey} to be used to decrypt the submissions,
   *                    wrapped inside an {@link Optional} when the form is encrypted, or
   *                    {@link Optional#empty()} otherwise
   * @return the {@link Submission} wrapped inside an {@link Optional} when it meets all the
   *     criteria, or {@link Optional#empty()} otherwise
   * @see #decrypt(Submission)
   */
  static Optional<Submission> parseSubmission(Path path, boolean isEncrypted, Optional<PrivateKey> privateKey, Map<Path, Document> docsByPath) {
    Path workingDir = isEncrypted ? createTempDirectory("briefcase") : path.getParent();
    Document cachedDocument = docsByPath.get(path);
    if (cachedDocument == null) log.warn("Not in cache: " + path);
    Optional<Document> maybeDocument = cachedDocument == null ? parse(path) : Optional.of(cachedDocument);
    return maybeDocument.flatMap(document -> {
      XmlElement root = XmlElement.of(document);
      SubmissionMetaData metaData = new SubmissionMetaData(root);

      // If all the needed parts are present, prepare the CipherFactory instance
      Optional<CipherFactory> cipherFactory = OptionalProduct.all(
          metaData.getInstanceId(),
          metaData.getBase64EncryptedKey(),
          privateKey
      ).map(CipherFactory::from);

      // If all the needed parts are present, decrypt the signature
      Optional<byte[]> signature = OptionalProduct.all(
          privateKey,
          metaData.getEncryptedSignature()
      ).map((pk, es) -> decrypt(signatureDecrypter(pk), decodeBase64(es)));

      Submission submission = Submission.notValidated(path, workingDir, root, metaData, cipherFactory, signature);
      return isEncrypted
          // If it's encrypted, validate the parsed contents with the attached signature
          ? decrypt(submission).map(s -> s.copy(ValidationStatus.of(isValid(submission, s))))
          // Return the original submission otherwise
          : Optional.of(submission);
    });
  }

  private static Optional<OffsetDateTime> readSubmissionDate(Path path, Map<Path, Document> docsByPath) {
    return parse(path).flatMap(document -> {
      docsByPath.put(path, document);
      XmlElement root = XmlElement.of(document);
      SubmissionMetaData metaData = new SubmissionMetaData(root);
      return metaData.getSubmissionDate();
    });
  }


  private static Optional<Submission> decrypt(Submission submission) {
    List<Path> mediaPaths = submission.getMediaPaths();

    if (mediaPaths.size() != submission.countMedia())
      // We must skip this submission because some media file is missing
      return Optional.empty();

    // Decrypt each attached media file in order
    mediaPaths.forEach(path -> decryptFile(path, submission.getWorkingDir(), submission.getNextCipher()));

    // Decrypt the submission
    Path decryptedSubmission = decryptFile(submission.getEncryptedFilePath(), submission.getWorkingDir(), submission.getNextCipher());

    // Parse the document and, if everything goes well, return a decripted copy of the submission
    return parse(decryptedSubmission).map(document -> submission.copy(decryptedSubmission, document));
  }

  private static Path decryptFile(Path encFile, Path workingDir, Cipher cipher) {
    Path decryptedFile = workingDir.resolve(stripFileExtension(encFile.getFileName().toString()));
    try (InputStream is = Files.newInputStream(encFile);
         CipherInputStream cis = new CipherInputStream(is, cipher);
         OutputStream os = Files.newOutputStream(decryptedFile)
    ) {
      byte[] buffer = new byte[2048];
      int len = cis.read(buffer);
      while (len != -1) {
        os.write(buffer, 0, len);
        len = cis.read(buffer);
      }
      os.flush();
      return decryptedFile;
    } catch (IOException e) {
      throw new CryptoException("Can't decrypt file", e);
    }
  }

  private static Optional<Document> parse(Path submission) {
    try (InputStream is = Files.newInputStream(submission);
         InputStreamReader isr = new InputStreamReader(is, "UTF-8")) {
      Document tempDoc = new Document();
      KXmlParser parser = new KXmlParser();
      parser.setInput(isr);
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
      tempDoc.parse(parser);
      return Optional.of(tempDoc);
    } catch (IOException | XmlPullParserException e) {
      log.warn("Can't parse submission {} {}", submission.getParent().getParent().getParent().getFileName(), submission.getParent().getFileName(), e);
      return Optional.empty();
    }
  }

  @SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
  private static byte[] decrypt(Cipher cipher, byte[] message) {
    try {
      return cipher.doFinal(message);
    } catch (IllegalBlockSizeException | BadPaddingException e) {
      throw new CryptoException("Can't decrypt message");
    }
  }

  private static boolean isValid(Submission submission, Submission decryptedSubmission) {
    return submission.validate(computeDigest(decryptedSubmission.buildSignature(submission)));
  }

  private static byte[] computeDigest(String message) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      md.update(message.getBytes("UTF-8"));
      return md.digest();
    } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
      throw new CryptoException("Can't compute digest", e);
    }
  }
}
