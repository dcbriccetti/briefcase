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

package org.opendatakit.briefcase.ui.reused;

import static java.awt.Color.GRAY;
import static java.awt.Color.LIGHT_GRAY;
import static javax.swing.JOptionPane.ERROR_MESSAGE;
import static javax.swing.JOptionPane.PLAIN_MESSAGE;
import static javax.swing.JOptionPane.YES_NO_OPTION;
import static javax.swing.JOptionPane.YES_OPTION;
import static javax.swing.JOptionPane.getFrameForComponent;
import static org.opendatakit.briefcase.ui.MainBriefcaseWindow.APP_NAME;
import static org.opendatakit.briefcase.ui.ScrollingStatusListDialog.showDialog;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.opendatakit.briefcase.model.FormStatus;

public class UI {
  private static final Font ic_receipt = FontUtils.getCustomFont("ic_receipt.ttf", 16f);

  @SuppressWarnings("checkstyle:AvoidEscapedUnicodeCharacters")
  public static JButton buildDetailButton(FormStatus form) {
    // Use custom fonts instead of png for easier scaling
    JButton button = new JButton("\uE900");
    button.setFont(ic_receipt); // custom font that overrides  with a receipt icon
    button.setToolTipText("View this form's status history");
    button.setMargin(new Insets(0, 0, 0, 0));

    button.setForeground(LIGHT_GRAY);
    button.addActionListener(__ -> {
      if (!form.getStatusHistory().isEmpty())
        showDialog(getFrameForComponent(button), form.getFormDefinition(), form.getStatusHistory());
    });
    return button;
  }

  public static JButton cellWithButton(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
    JButton button = (JButton) value;
    button.setOpaque(true);
    button.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
    return button;
  }

  /**
   * Pops an informative dialog up with some sensible defaults:
   * <ul>
   * <li>uses {@link org.opendatakit.briefcase.ui.MainBriefcaseWindow#APP_NAME} as the title</li>
   * <li>blocks the UI</li>
   * </ul>
   */
  public static void infoMessage(String message) {
    infoMessage(APP_NAME, message, true);
  }

  /**
   * Pops an informative dialog up
   */
  public static void infoMessage(String title, String message, boolean blockUI) {
    Runnable dialog = () -> JOptionPane.showMessageDialog(buildDialogParent(), message, title, PLAIN_MESSAGE);
    if (blockUI)
      dialog.run();
    else
      SwingUtilities.invokeLater(dialog);
  }

  /**
   * Pops a confirmation (YES/NO) dialog up with some sensible defaults:
   * <ul>
   * <li>uses {@link org.opendatakit.briefcase.ui.MainBriefcaseWindow#APP_NAME} as the title</li>
   * </ul>
   * <p>
   * Confirmation dialogs always block the UI.
   */
  public static boolean confirm(String message) {
    return confirm(APP_NAME, message);
  }

  /**
   * Pops a confirmation (YES/NO) dialog up.
   * <p>
   * Confirmation dialogs always block the UI.
   */
  public static boolean confirm(String title, String message) {
    return JOptionPane.showConfirmDialog(buildDialogParent(), message, title, YES_NO_OPTION, PLAIN_MESSAGE) == YES_OPTION;
  }

  /**
   * Pops an error dialog up with some sensible defaults:
   * <ul>
   * <li>blocks the UI</li>
   * </ul>
   */
  public static void errorMessage(String title, String message) {
    errorMessage(title, message, true);
  }

  /**
   * Pops an error dialog up.
   */
  public static void errorMessage(String title, String message, boolean blockUI) {
    Runnable dialog = () -> JOptionPane.showMessageDialog(buildDialogParent(), buildScrollPane(message), title, ERROR_MESSAGE);
    if (blockUI)
      dialog.run();
    else
      SwingUtilities.invokeLater(dialog);
  }

  private static JDialog buildDialogParent() {
    JDialog dialog = new JDialog();
    // We want all dialogs show on top
    dialog.setAlwaysOnTop(true);
    return dialog;
  }

  private static JScrollPane buildScrollPane(String message) {
    // create a n-character wide label for aiding layout calculations...
    // the dialog box will display this width of text.
    JLabel probe = new JLabel("MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM");

    JTextArea textArea = new JTextArea(message);
    textArea.setEditable(false);
    textArea.setLineWrap(true);
    textArea.setWrapStyleWord(true);
    // Take font and colors from the probe
    textArea.setFont(probe.getFont());
    textArea.setBackground(probe.getBackground());
    textArea.setForeground(probe.getForeground());

    JScrollPane scrollPane = new JScrollPane(textArea);

    // don't show the gray border of the scroll pane
    // unless we are showing the scroll bar, in which case we do show it.
    scrollPane.setBorder(BorderFactory.createEmptyBorder());
    scrollPane.getVerticalScrollBar().addComponentListener(new ComponentAdapter() {
      @Override
      public void componentHidden(ComponentEvent component) {
        if (component.getComponent().equals(scrollPane.getVerticalScrollBar())) {
          scrollPane.setBorder(BorderFactory.createEmptyBorder());
        }
      }

      @Override
      public void componentShown(ComponentEvent component) {
        if (component.getComponent().equals(scrollPane.getVerticalScrollBar())) {
          scrollPane.setBorder(BorderFactory.createLineBorder(GRAY));
        }
      }
    });

    Dimension dimension = probe.getPreferredSize();
    dimension.setSize(dimension.getWidth(), 5.3 * dimension.getHeight());
    scrollPane.setMinimumSize(dimension);
    scrollPane.setPreferredSize(dimension);
    return scrollPane;
  }
}
