/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2017 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.controls.txn;

import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.IWorkbenchPart;
import org.jkiss.dbeaver.core.DBeaverUI;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.*;
import org.jkiss.dbeaver.model.qm.QMTransactionState;
import org.jkiss.dbeaver.model.qm.QMUtils;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
import org.jkiss.dbeaver.registry.DataSourceRegistry;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.actions.datasource.DataSourceCommitHandler;
import org.jkiss.dbeaver.ui.actions.datasource.DataSourceRollbackHandler;
import org.jkiss.utils.ArrayUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class PendingTransactionsDialog extends TransactionInfoDialog {

    private static final String DIALOG_ID = "DBeaver.PendingTransactionsDialog";//$NON-NLS-1$
    private Tree contextTree;
    private DBCExecutionContext selectedContext;
    private Button commitButton;
    private Button rollbackButton;

    public PendingTransactionsDialog(Shell parentShell, IWorkbenchPart activePart) {
        super(parentShell, activePart);
    }

    @Override
    protected boolean isResizable() {
    	return true;
    }

    @Override
    protected DBCExecutionContext getCurrentContext() {
        return selectedContext;
    }

    protected IDialogSettings getDialogBoundsSettings()
    {
        return UIUtils.getDialogSettings(DIALOG_ID);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        getShell().setText("Pending transactions");

        Composite composite = (Composite) super.createDialogArea(parent);

        contextTree = new Tree(composite, SWT.FULL_SELECTION | SWT.BORDER);
        contextTree.setHeaderVisible(true);
        contextTree.setLinesVisible(true);
        TreeColumn colName = new TreeColumn(contextTree, SWT.NONE);
        colName.setText("Connection");
        TreeColumn colTxn = new TreeColumn(contextTree, SWT.RIGHT);
        colTxn.setText("Transaction");
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.heightHint = contextTree.getHeaderHeight() + contextTree.getItemHeight() * 5;
        contextTree.setLayoutData(gd);
        contextTree.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (e.item != null && e.item.getData() instanceof DBCExecutionContext) {
                    selectedContext = (DBCExecutionContext) e.item.getData();
                } else {
                    selectedContext = null;
                }
                boolean hasTransaction = selectedContext != null && QMUtils.isTransactionActive(selectedContext);
                commitButton.setEnabled(hasTransaction);
                rollbackButton.setEnabled(hasTransaction);
                logViewer.setFilter(createContextFilter(selectedContext));
                logViewer.refresh();
            }
        });

        {
            Composite controlPanel = UIUtils.createPlaceholder(composite, 3, 5);
            controlPanel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            final Button showAllCheck = UIUtils.createCheckbox(controlPanel, "Show all connections", "Show all datasource connections. Otherwise shows only transactional connections.", false, 1);
            showAllCheck.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    loadContexts(showAllCheck.getSelection());
                }
            });
            gd = new GridData(GridData.FILL_HORIZONTAL);
            gd.grabExcessHorizontalSpace = true;
            showAllCheck.setLayoutData(gd);
            commitButton = UIUtils.createPushButton(controlPanel, "Commit", DBeaverIcons.getImage(UIIcon.TXN_COMMIT));
            commitButton.setEnabled(false);
            commitButton.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    endTransaction(true);
                }
            });
            rollbackButton = UIUtils.createPushButton(controlPanel, "Rollback", DBeaverIcons.getImage(UIIcon.TXN_ROLLBACK));
            rollbackButton.setEnabled(false);
            rollbackButton.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    endTransaction(false);
                }
            });
        }

        super.createTransactionLogPanel(composite);

        loadContexts(false);

        return parent;
    }

    private void endTransaction(boolean commit) {
        if (selectedContext == null) {
            return;
        }
        if (commit) {
            DataSourceCommitHandler.execute(selectedContext);
        } else {
            DataSourceRollbackHandler.execute(selectedContext);
        }
        commitButton.setEnabled(false);
        rollbackButton.setEnabled(false);
    }

    private void loadContexts(boolean showAllContexts) {
        contextTree.removeAll();

        // Load all open context
        for (DataSourceDescriptor dataSource : DataSourceRegistry.getAllDataSources()) {
            if (!dataSource.isConnected() || dataSource.getDataSource() == null) {
                continue;
            }
            DBCExecutionContext[] allContexts = dataSource.getDataSource().getAllContexts();
            if (ArrayUtils.isEmpty(allContexts)) {
                continue;
            }
            List<DBCExecutionContext> txnContexts = new ArrayList<>();
            for (DBCExecutionContext context : allContexts) {
                if (showAllContexts || QMUtils.isTransactionActive(context)) {
                    txnContexts.add(context);
                }
            }
            if (txnContexts.isEmpty()) {
                continue;
            }
            TreeItem dsItem = new TreeItem(contextTree, SWT.NONE);
            dsItem.setText(dataSource.getName());
            dsItem.setImage(DBeaverIcons.getImage(dataSource.getObjectImage()));
            dsItem.setData(dataSource);

            for (DBCExecutionContext context : txnContexts) {
                QMTransactionState txnState = QMUtils.getTransactionState(context);
                TreeItem contextItem = new TreeItem(dsItem, SWT.NONE);
                contextItem.setText(0, context.getContextName());
                String stateString = String.valueOf(txnState.getUpdateCount()) + "/" + String.valueOf(txnState.getExecuteCount());
                contextItem.setText(1, stateString);
                contextItem.setData(context);
            }
            dsItem.setExpanded(true);
        }

        UIUtils.packColumns(contextTree);
    }

    public static void showDialog(Shell shell) {
        IWorkbenchPart activePart = DBeaverUI.getActiveWorkbenchWindow().getActivePage().getActivePart();
        if (activePart == null) {
            UIUtils.showErrorDialog(
                shell,
                "No active part",
                "No active part.");
        } else {
            final PendingTransactionsDialog dialog = new PendingTransactionsDialog(shell, activePart);
            dialog.open();
        }
    }

}
