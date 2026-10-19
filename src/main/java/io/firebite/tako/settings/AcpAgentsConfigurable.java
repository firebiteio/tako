package io.firebite.tako.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.ListTableModel;
import io.firebite.tako.TakoBundle;
import io.firebite.tako.agent.AgentRegistry;
import io.firebite.tako.chat.ChatService;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

/** Settings | Tools | Tako | ACP Agents, for any agent that speaks the Agent Client Protocol, by command line. */
public final class AcpAgentsConfigurable implements Configurable {
  private ListTableModel<Row> model;

  @Override
  public @Nls String getDisplayName() {
    return TakoBundle.message("configurable.acp");
  }

  @Override
  public @Nullable JComponent createComponent() {
    this.model = new ListTableModel<>(new Column(TakoBundle.message("settings.acp.column.name"), 0),
      new Column(TakoBundle.message("settings.acp.column.command"), 1));
    TableView<Row> table = new TableView<>(this.model);
    table.setShowGrid(false);
    JComponent tablePanel = ToolbarDecorator.createDecorator(table)
      .setAddAction(button -> {
        this.model.addRow(new Row("", ""));
        int row = this.model.getRowCount() - 1;
        table.getSelectionModel().setSelectionInterval(row, row);
        table.editCellAt(row, 0);
      })
      .disableUpDownActions()
      .createPanel();
    JComponent panel = FormBuilder.createFormBuilder()
      .addComponent(new JBLabel(TakoBundle.message("settings.acp.hint")))
      .addComponentFillVertically(tablePanel, 0)
      .getPanel();
    this.reset();
    return panel;
  }

  @Override
  public boolean isModified() {
    return !this.lines().equals(ChatSettings.state().acpAgents.trim());
  }

  @Override
  public void apply() {
    ChatSettings.state().acpAgents = this.lines();
    ChatService.postSettingsToOpenProjects();
  }

  @Override
  public void reset() {
    List<Row> rows = new ArrayList<>();
    for (AgentRegistry.AcpAgent agent : AgentRegistry.acpAgents(ChatSettings.state().acpAgents)) {
      rows.add(new Row(agent.name(), agent.command()));
    }
    this.model.setItems(rows);
  }

  /** The table as stored, one {@code Name = command line} per line. Skips rows missing either part. */
  private String lines() {
    List<String> lines = new ArrayList<>();
    for (Row row : this.model.getItems()) {
      String name = row.name.trim().replace("=", "");
      String command = row.command.trim();
      if (!name.isEmpty() && !command.isEmpty()) {
        lines.add(name + " = " + command);
      }
    }
    return String.join("\n", lines);
  }

  private static final class Row {
    private String name;
    private String command;

    Row(String name, String command) {
      this.name = name;
      this.command = command;
    }
  }

  private static final class Column extends ColumnInfo<Row, String> {
    private final int index;

    Column(String title, int index) {
      super(title);
      this.index = index;
    }

    @Override
    public String valueOf(Row row) {
      return this.index == 0 ? row.name : row.command;
    }

    @Override
    public void setValue(Row row, String value) {
      if (this.index == 0) {
        row.name = value;
      } else {
        row.command = value;
      }
    }

    @Override
    public boolean isCellEditable(Row row) {
      return true;
    }
  }
}
