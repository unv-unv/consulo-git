/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.rebase;

import consulo.application.dumb.DumbAware;
import consulo.dataContext.DataProvider;
import consulo.git.localize.GitLocalize;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.CopyProvider;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.action.CommonShortcuts;
import consulo.ui.ex.awt.*;
import consulo.ui.ex.awt.speedSearch.SpeedSearchUtil;
import consulo.ui.ex.awt.speedSearch.TableSpeedSearch;
import consulo.ui.ex.awt.table.ComboBoxTableCellRenderer;
import consulo.ui.ex.awt.table.JBTable;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ListWithSelection;
import consulo.util.dataholder.Key;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitUtil;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.io.IOException;
import java.util.*;

/**
 * Interactive rebase editor. It allows reordering of the entries and changing commit status.
 */
public class GitRebaseEditor extends DialogWrapper implements DataProvider {
    @Nonnull
    private final Project myProject;
    @Nonnull
    private final VirtualFile myRoot;

    @Nonnull
    private final MyTableModel myTableModel;
    @Nonnull
    private final JBTable myCommitsTable;
    @Nonnull
    private final CopyProvider myCopyProvider;

    protected GitRebaseEditor(@Nonnull Project project, @Nonnull VirtualFile gitRoot, @Nonnull List<GitRebaseEntry> entries)
        throws IOException {

        super(project, true);
        myProject = project;
        myRoot = gitRoot;
        setTitle(GitLocalize.rebaseEditorTitle());
        setOKButtonText(GitLocalize.rebaseEditorButton());

        myTableModel = new MyTableModel(entries);
        myCommitsTable = new JBTable(myTableModel);
        myCommitsTable.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        myCommitsTable.setIntercellSpacing(JBUI.emptySize());

        JComboBox<GitRebaseEntry.Action> editorComboBox = new ComboBox<>();
        for (GitRebaseEntry.Action action : GitRebaseEntry.Action.values()) {
            editorComboBox.addItem(action);
        }
        TableColumn actionColumn = myCommitsTable.getColumnModel().getColumn(MyTableModel.ACTION_COLUMN);
        actionColumn.setCellEditor(new DefaultCellEditor(editorComboBox));
        actionColumn.setCellRenderer(ComboBoxTableCellRenderer.INSTANCE);

        myCommitsTable.setDefaultRenderer(String.class, new ColoredTableCellRenderer() {
            @Override
            protected void customizeCellRenderer(JTable table, Object value, boolean selected, boolean hasFocus, int row, int column) {
                if (value != null) {
                    append(value.toString());
                    SpeedSearchUtil.applySpeedSearchHighlighting(myCommitsTable, this, true, selected);
                }
            }
        });

        myTableModel.addTableModelListener(e -> validateFields());

        installSpeedSearch();
        myCopyProvider = new MyCopyProvider();

        adjustColumnWidth(0);
        adjustColumnWidth(1);
        init();
    }

    private void installSpeedSearch() {
        new TableSpeedSearch(myCommitsTable, (o, cell) -> cell.column == 0 ? null : String.valueOf(o));
    }

    @Nullable
    @Override
    @RequiredUIAccess
    public JComponent getPreferredFocusedComponent() {
        return myCommitsTable;
    }

    private void adjustColumnWidth(int columnIndex) {
        int contentWidth = myCommitsTable.getExpandedColumnWidth(columnIndex) + UIUtil.DEFAULT_HGAP;
        TableColumn column = myCommitsTable.getColumnModel().getColumn(columnIndex);
        column.setMaxWidth(contentWidth);
        column.setPreferredWidth(contentWidth);
    }

    private void validateFields() {
        List<GitRebaseEntry> entries = myTableModel.myEntries;
        if (entries.size() == 0) {
            setErrorText(GitLocalize.rebaseEditorInvalidEntryset());
            setOKActionEnabled(false);
            return;
        }
        int i = 0;
        while (i < entries.size() && entries.get(i).getAction() == GitRebaseEntry.Action.skip) {
            i++;
        }
        if (i < entries.size()) {
            GitRebaseEntry.Action action = entries.get(i).getAction();
            if (action == GitRebaseEntry.Action.squash || action == GitRebaseEntry.Action.fixup) {
                setErrorText(GitLocalize.rebaseEditorInvalidSquash(StringUtil.toLowerCase(action.name())));
                setOKActionEnabled(false);
                return;
            }
        }
        clearErrorText();
        setOKActionEnabled(true);
    }

    @Override
    protected JComponent createCenterPanel() {
        return ToolbarDecorator.createDecorator(myCommitsTable)
            .disableAddAction()
            .disableRemoveAction()
            .addExtraAction(new MyDiffAction())
            .setMoveUpAction(new MoveUpDownActionListener(MoveDirection.UP))
            .setMoveDownAction(new MoveUpDownActionListener(MoveDirection.DOWN))
            .createPanel();
    }

    @Override
    protected String getDimensionServiceKey() {
        return getClass().getName();
    }

    @Override
    protected String getHelpId() {
        return "reference.VersionControl.Git.RebaseCommits";
    }

    @Nonnull
    public List<GitRebaseEntry> getEntries() {
        return myTableModel.myEntries;
    }

    @Nullable
    @Override
    public Object getData(@Nonnull Key<?> dataId) {
        if (CopyProvider.KEY == dataId) {
            return myCopyProvider;
        }
        return null;
    }

    private class MyTableModel extends AbstractTableModel implements EditableModel {
        private static final int ACTION_COLUMN = 0;
        private static final int HASH_COLUMN = 1;
        private static final int SUBJECT_COLUMN = 2;

        @Nonnull
        private final List<GitRebaseEntry> myEntries;
        private int[] myLastEditableSelectedRows = new int[]{};

        MyTableModel(@Nonnull List<GitRebaseEntry> entries) {
            myEntries = entries;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == ACTION_COLUMN ? ListWithSelection.class : String.class;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case ACTION_COLUMN -> GitLocalize.rebaseEditorActionColumn().get();
                case HASH_COLUMN -> GitLocalize.rebaseEditorCommitColumn().get();
                case SUBJECT_COLUMN -> GitLocalize.rebaseEditorCommentColumn().get();
                default -> throw new IllegalArgumentException("Unsupported column index: " + column);
            };
        }

        @Override
        public int getRowCount() {
            return myEntries.size();
        }

        @Override
        public int getColumnCount() {
            return SUBJECT_COLUMN + 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            GitRebaseEntry e = myEntries.get(rowIndex);
            return switch (columnIndex) {
                case ACTION_COLUMN -> new ListWithSelection<>(Arrays.asList(GitRebaseEntry.Action.values()), e.getAction());
                case HASH_COLUMN -> e.getCommit();
                case SUBJECT_COLUMN -> e.getSubject();
                default -> throw new IllegalArgumentException("Unsupported column index: " + columnIndex);
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            assert columnIndex == ACTION_COLUMN;

            if (ArrayUtil.indexOf(myLastEditableSelectedRows, rowIndex) > -1) {
                ContiguousIntIntervalTracker intervalBuilder = new ContiguousIntIntervalTracker();
                for (int lastEditableSelectedRow : myLastEditableSelectedRows) {
                    intervalBuilder.track(lastEditableSelectedRow);
                    setRowAction(aValue, lastEditableSelectedRow, columnIndex);
                }
                setSelection(intervalBuilder);
            }
            else {
                setRowAction(aValue, rowIndex, columnIndex);
            }
        }

        @Override
        public void addRow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void exchangeRows(int oldIndex, int newIndex) {
            GitRebaseEntry movingElement = myEntries.remove(oldIndex);
            myEntries.add(newIndex, movingElement);
            fireTableRowsUpdated(Math.min(oldIndex, newIndex), Math.max(oldIndex, newIndex));
        }

        @Override
        public boolean canExchangeRows(int oldIndex, int newIndex) {
            return true;
        }

        @Override
        public void removeRow(int idx) {
            throw new UnsupportedOperationException();
        }

        @Nullable
        public String getStringToCopy(int row) {
            if (row < 0 || row >= myEntries.size()) {
                return null;
            }
            GitRebaseEntry e = myEntries.get(row);
            return e.getCommit() + " " + e.getSubject();
        }

        private void setSelection(@Nonnull ContiguousIntIntervalTracker intervalBuilder) {
            myCommitsTable.getSelectionModel().setSelectionInterval(intervalBuilder.getMin(), intervalBuilder.getMax());
        }

        private void setRowAction(@Nonnull Object aValue, int rowIndex, int columnIndex) {
            GitRebaseEntry e = myEntries.get(rowIndex);
            e.setAction((GitRebaseEntry.Action)aValue);
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            myLastEditableSelectedRows = myCommitsTable.getSelectedRows();
            return columnIndex == ACTION_COLUMN;
        }

        public void moveRows(@Nonnull int[] rows, @Nonnull MoveDirection direction) {
            myCommitsTable.removeEditor();

            ContiguousIntIntervalTracker selectionInterval = new ContiguousIntIntervalTracker();
            ContiguousIntIntervalTracker rowsUpdatedInterval = new ContiguousIntIntervalTracker();

            for (int row : direction.preprocessRowIndexes(rows)) {
                int targetIndex = row + direction.offset();
                assertIndexInRange(row, targetIndex);

                Collections.swap(myEntries, row, targetIndex);

                rowsUpdatedInterval.track(targetIndex, row);
                selectionInterval.track(targetIndex);
            }

            if (selectionInterval.hasValues()) {
                setSelection(selectionInterval);
                fireTableRowsUpdated(rowsUpdatedInterval.getMin(), rowsUpdatedInterval.getMax());
            }
        }

        private void assertIndexInRange(int... rowIndexes) {
            for (int rowIndex : rowIndexes) {
                assert rowIndex >= 0;
                assert rowIndex < myEntries.size();
            }
        }
    }

    private static class ContiguousIntIntervalTracker {
        private Integer myMin = null;
        private Integer myMax = null;
        private static final int UNSET_VALUE = -1;

        public Integer getMin() {
            return myMin == null ? UNSET_VALUE : myMin;
        }

        public Integer getMax() {
            return myMax == null ? UNSET_VALUE : myMax;
        }

        public void track(int... entries) {
            for (int entry : entries) {
                checkMax(entry);
                checkMin(entry);
            }
        }

        private void checkMax(int entry) {
            if (null == myMax || entry > myMax) {
                myMax = entry;
            }
        }

        private void checkMin(int entry) {
            if (null == myMin || entry < myMin) {
                myMin = entry;
            }
        }

        public boolean hasValues() {
            return (null != myMin && null != myMax);
        }
    }

    private enum MoveDirection {
        UP,
        DOWN;

        public int offset() {
            return this == UP ? -1 : +1;
        }

        public int[] preprocessRowIndexes(int[] selection) {
            int[] copy = selection.clone();
            Arrays.sort(copy);
            return this == UP ? copy : ArrayUtil.reverseArray(copy);
        }
    }

    private class MyDiffAction extends ToolbarDecorator.ElementActionButton implements DumbAware {
        MyDiffAction() {
            super("View", "View commit contents", PlatformIconGroup.actionsDiff());
            registerCustomShortcutSet(CommonShortcuts.getDiff(), myCommitsTable);
        }

        @Override
        @RequiredUIAccess
        public void actionPerformed(@Nonnull AnActionEvent e) {
            int row = myCommitsTable.getSelectedRow();
            assert 0 <= row && row < myTableModel.getRowCount();
            GitRebaseEntry entry = myTableModel.myEntries.get(row);
            GitUtil.showSubmittedFiles(myProject, entry.getCommit(), myRoot, false, false);
        }

        @Override
        public boolean isEnabled() {
            return super.isEnabled() && myCommitsTable.getSelectedRowCount() == 1;
        }
    }

    private class MoveUpDownActionListener implements AnActionButtonRunnable {
        private final MoveDirection direction;

        public MoveUpDownActionListener(@Nonnull MoveDirection direction) {
            this.direction = direction;
        }

        @Override
        @RequiredUIAccess
        public void run(AnActionButton button) {
            myTableModel.moveRows(myCommitsTable.getSelectedRows(), direction);
        }
    }

    private class MyCopyProvider extends TextCopyProvider {
        @Nullable
        @Override
        public Collection<String> getTextLinesToCopy() {
            if (myCommitsTable.getSelectedRowCount() > 0) {
                List<String> lines = new ArrayList<>();
                for (int row : myCommitsTable.getSelectedRows()) {
                    lines.add(myTableModel.getStringToCopy(row));
                }
                return lines;
            }
            return null;
        }
    }
}
