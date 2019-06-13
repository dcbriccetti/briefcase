/*
 * Copyright (C) 2019 Nafundi
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

package org.opendatakit.briefcase.ui.reused.transfer.sourcetarget.source;

import static java.awt.Cursor.HAND_CURSOR;
import static java.awt.Cursor.getPredefinedCursor;
import static java.util.stream.Collectors.toList;
import static javax.swing.SwingUtilities.invokeLater;
import static org.opendatakit.briefcase.ui.reused.SwingUtils.uncheckedBrowse;
import static org.opendatakit.briefcase.ui.reused.UI.removeAllMouseListeners;

import java.awt.Container;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.JLabel;
import org.bushe.swing.event.EventBus;
import org.opendatakit.briefcase.model.BriefcasePreferences;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.pull.PullEvent;
import org.opendatakit.briefcase.pull.aggregate.Cursor;
import org.opendatakit.briefcase.pull.aggregate.PullFromAggregate;
import org.opendatakit.briefcase.pull.aggregate.PullFromAggregateResult;
import org.opendatakit.briefcase.reused.BriefcaseException;
import org.opendatakit.briefcase.reused.http.Http;
import org.opendatakit.briefcase.reused.job.JobsRunner;
import org.opendatakit.briefcase.reused.transfer.AggregateServer;
import org.opendatakit.briefcase.reused.transfer.RemoteServer.Test;
import org.opendatakit.briefcase.transfer.TransferForms;
import org.opendatakit.briefcase.ui.reused.MouseAdapterBuilder;
import org.opendatakit.briefcase.ui.reused.transfer.sourcetarget.AggregateServerDialog;

/**
 * Represents an ODK Aggregate server as a source of forms for the Pull UI Panel.
 */
public class Aggregate implements PullSource<AggregateServer> {
  private final Http http;
  private final Consumer<PullSource> consumer;
  private Test<AggregateServer> serverTester;
  private String requiredPermission;
  private AggregateServer server;

  Aggregate(Http http, Test<AggregateServer> serverTester, String requiredPermission, Consumer<PullSource> consumer) {
    this.http = http;
    this.serverTester = serverTester;
    this.requiredPermission = requiredPermission;
    this.consumer = consumer;
  }

  @Override
  public void onSelect(Container ignored) {
    AggregateServerDialog dialog = AggregateServerDialog.empty(serverTester, requiredPermission);
    dialog.onConnect(this::set);
    dialog.getForm().setVisible(true);
  }

  @Override
  public void set(AggregateServer server) {
    this.server = server;
    consumer.accept(this);
  }

  @Override
  public boolean accepts(Object o) {
    return o instanceof AggregateServer;
  }

  @Override
  public List<FormStatus> getFormList() {
    return http.execute(server.getFormListRequest())
        .map(formDefs -> formDefs.stream().map(FormStatus::new).collect(toList()))
        .orElseThrow(() -> new BriefcaseException("Can't get forms list from server"));
  }

  @Override
  public void storeSourcePrefs(BriefcasePreferences prefs, boolean storePasswords) {
    server.storeSourcePrefs(prefs, storePasswords);
  }

  @Override
  public JobsRunner pull(TransferForms forms, BriefcasePreferences prefs) {
    PullFromAggregate pullOp = new PullFromAggregate(
        http,
        server,
        prefs.getBriefcaseDir().orElseThrow(BriefcaseException::new),
        false,
        EventBus::publish
    );

    return JobsRunner.launchAsync(
        forms.map(form -> pullOp.pull(
            form,
            prefs.getResumeLastPull().orElse(false) ? Cursor.readPrefs(form, prefs) : Optional.empty()
        )),
        this::onSuccess,
        this::onError
    ).onComplete(() -> EventBus.publish(new PullEvent.PullComplete()));
  }

  private void onSuccess(PullFromAggregateResult result) {
    EventBus.publish(PullEvent.Success.of(result.getForm(), server, result.getLastCursor()));
  }

  private void onError(Throwable e) {
    log.error("Error pulling forms", e);
    EventBus.publish(new PullEvent.Failure());
  }

  @Override
  public boolean canBeReloaded() {
    return true;
  }

  @Override
  public String getDescription() {
    return server.getBaseUrl().toString();
  }

  @Override
  public void decorate(JLabel label) {
    label.setText("<html><a href=\"" + server.getBaseUrl().toString() + "\">" + getDescription() + "</a></html>");
    label.setCursor(getPredefinedCursor(HAND_CURSOR));
    removeAllMouseListeners(label);
    label.addMouseListener(new MouseAdapterBuilder()
        .onClick(__ -> invokeLater(() -> uncheckedBrowse(server.getBaseUrl())))
        .build());
  }

  @Override
  public String toString() {
    return "Aggregate server";
  }
}
