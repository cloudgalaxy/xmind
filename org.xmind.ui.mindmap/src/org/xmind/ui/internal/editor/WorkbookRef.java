/* ******************************************************************************
 * Copyright (c) 2006-2008 XMind Ltd. and others.
 * 
 * This file is a part of XMind 3. XMind releases 3 and
 * above are dual-licensed under the Eclipse Public License (EPL),
 * which is available at http://www.eclipse.org/legal/epl-v10.html
 * and the GNU Lesser General Public License (LGPL), 
 * which is available at http://www.gnu.org/licenses/lgpl.html
 * See http://www.xmind.net/license.html for details.
 * 
 * Contributors:
 *     XMind Ltd. - initial API and implementation
 *******************************************************************************/
package org.xmind.ui.internal.editor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.xmind.core.Core;
import org.xmind.core.CoreException;
import org.xmind.core.IEncryptionHandler;
import org.xmind.core.IWorkbook;
import org.xmind.core.event.ICoreEventListener;
import org.xmind.core.event.ICoreEventRegistration;
import org.xmind.core.event.ICoreEventSource;
import org.xmind.core.event.ICoreEventSource2;
import org.xmind.core.event.ICoreEventSupport;
import org.xmind.core.io.DirectoryStorage;
import org.xmind.core.io.IStorage;
import org.xmind.core.marker.IMarkerSheet;
import org.xmind.gef.command.ICommandStack;
import org.xmind.ui.internal.MindMapUIPlugin;
import org.xmind.ui.mindmap.IWorkbookRef;
import org.xmind.ui.mindmap.MindMapUI;
import org.xmind.ui.prefs.PrefConstants;

/**
 * @author Frank Shaka
 * 
 */
public class WorkbookRef implements IWorkbookRef, IPropertyChangeListener,
        IPartListener {

    private static final List<IEditorPart> EMPTY_LIST = Collections.emptyList();

    private static final String SUBDIR_WORKBOOK = "workbooks"; //$NON-NLS-1$

    private Object key;

    private List<IEditorPart> referrers;

    private IWorkbook workbook;

    private ICommandStack commandStack;

    private IWorkbookLoader workbookLoader;

    private IWorkbookSaver workbookSaver;

    private ICoreEventRegistration forceDirtyReg;

    private Set<IWorkbenchPage> hookedPages;

    public WorkbookRef() {
        MindMapUIPlugin.getDefault().getPreferenceStore()
                .addPropertyChangeListener(this);
    }

    public void setKey(Object key) {
        this.key = key;
    }

    public WorkbookRef setWorkbookLoader(IWorkbookLoader workbookLoader) {
        this.workbookLoader = workbookLoader;
        return this;
    }

    public WorkbookRef setWorkbookSaver(IWorkbookSaver workbookSaver) {
        this.workbookSaver = workbookSaver;
        return this;
    }

    public boolean isReady() {
        return workbook != null || workbookLoader != null;
    }

    public void setWorkbook(IWorkbook workbook) {
        if (workbook == this.workbook)
            return;

        this.workbook = workbook;
        if (workbook != null) {
            IMarkerSheet markerSheet = workbook.getMarkerSheet();
            if (markerSheet != null) {
                markerSheet.setParentSheet(MindMapUI.getResourceManager()
                        .getUserMarkerSheet());
            }
        }
    }

    public void setCommandStack(ICommandStack commandStack) {
        this.commandStack = commandStack;
        if (commandStack != null) {
            commandStack.setUndoLimit(Math.max(MindMapUIPlugin.getDefault()
                    .getPreferenceStore().getInt(PrefConstants.UNDO_LIMIT), 1));
        }
    }

    public Object getKey() {
        return key;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.xmind.ui.internal.editor.IWorkbookReference#getCommandStack()
     */
    public ICommandStack getCommandStack() {
        return commandStack;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.xmind.ui.internal.editor.IWorkbookReference#getWorkbook()
     */
    public IWorkbook getWorkbook() {
        return workbook;
    }

    public void setSelection(ISelection selection, boolean reveal,
            boolean forceFocus) {
        if (referrers != null && !referrers.isEmpty()) {
            IEditorPart editor = referrers.get(0);
            if (editor != null) {
                ISelectionProvider selectionProvider = editor.getSite()
                        .getSelectionProvider();
                if (selectionProvider != null) {
                    selectionProvider.setSelection(selection);
                }
                if (forceFocus) {
                    editor.getSite().getPage().activate(editor);
                    Shell shell = editor.getSite().getShell();
                    if (shell != null && !shell.isDisposed()) {
                        shell.setActive();
                    }
                } else if (reveal) {
                    editor.getSite().getPage().bringToTop(editor);
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.xmind.ui.internal.editor.IWorkbookReference#refresh()
     */
    public void refresh() {
        //not implemented yet
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.core.runtime.IAdaptable#getAdapter(java.lang.Class)
     */
    public Object getAdapter(Class adapter) {
        if (adapter == IWorkbook.class)
            return getWorkbook();
        if (adapter == ICommandStack.class)
            return getCommandStack();
        return null;
    }

    public void dispose(boolean closeWorkbook) {
        MindMapUIPlugin.getDefault().getPreferenceStore()
                .removePropertyChangeListener(this);
        if (commandStack != null) {
            commandStack.dispose();
            commandStack = null;
        }
        key = null;
        referrers = null;
        if (workbook != null && closeWorkbook) {
            closeWorkbook(workbook);
        }
        workbook = null;
    }

    private void closeWorkbook(IWorkbook workbook) {
        ICoreEventSupport support = (ICoreEventSupport) workbook
                .getAdapter(ICoreEventSupport.class);
        if (support != null) {
            support.dispatchTargetChange((ICoreEventSource) workbook,
                    MindMapUI.WorkbookClose, this);
        }
        IStorage storage = workbook.getTempStorage();
        if (storage != null) {
            storage.clear();
        }
    }

    public void addReferrer(IEditorPart referrer) {
        if (referrers == null)
            referrers = new ArrayList<IEditorPart>(2);
        referrers.add(0, referrer);
        addPartListener(referrer);
    }

    private void addPartListener(IEditorPart editor) {
        IWorkbenchPage page = editor.getSite().getPage();
        if (hookedPages == null) {
            hookedPages = new HashSet<IWorkbenchPage>(2);
        }
        if (!hookedPages.contains(page)) {
            hookedPages.add(page);
            page.addPartListener(this);
        }
    }

    public List<IEditorPart> getReferrers() {
        return referrers == null ? EMPTY_LIST : referrers;
    }

    public int getNumReferrers() {
        return referrers == null ? 0 : referrers.size();
    }

    public boolean isReferred() {
        return referrers != null && !referrers.isEmpty();
    }

    public void removeReferrer(IEditorPart referrer) {
        if (referrers == null)
            return;
        referrers.remove(referrer);
        removePartListener(referrer);
        if (referrers.isEmpty())
            referrers = null;
    }

    private void removePartListener(IEditorPart editor) {
        if (hookedPages == null)
            return;
        IWorkbenchPage page = editor.getSite().getPage();
        if (hookedPages.remove(page)) {
            page.removePartListener(this);
        }
        if (hookedPages.isEmpty()) {
            hookedPages = null;
        }
    }

    public List<IEditorPart> getOpenedEditors() {
        if (referrers == null)
            return EMPTY_LIST;
        return Collections.unmodifiableList(referrers);
    }

    public void forceDirty() {
        if (forceDirtyReg == null || !forceDirtyReg.isValid()) {
            if (workbook instanceof ICoreEventSource2) {
                forceDirtyReg = ((ICoreEventSource2) workbook)
                        .registerOnceCoreEventListener(
                                Core.WorkbookPreSaveOnce,
                                ICoreEventListener.NULL);
            } else {
                forceDirtyReg = null;
            }
        }
    }

    public boolean isForceDirty() {
        return forceDirtyReg != null && forceDirtyReg.isValid();
    }

    public void loadWorkbook(IEncryptionHandler encryptionHandler,
            IProgressMonitor monitor) throws IOException, CoreException,
            org.eclipse.core.runtime.CoreException {
        loadWorkbook(createStorage(), encryptionHandler, monitor);
    }

    public void loadWorkbook(IStorage storage,
            IEncryptionHandler encryptionHandler, IProgressMonitor monitor)
            throws IOException, CoreException,
            org.eclipse.core.runtime.CoreException {
        if (workbook != null)
            return;

        if (workbookLoader == null)
            throw new org.eclipse.core.runtime.CoreException(new Status(
                    IStatus.ERROR, MindMapUIPlugin.PLUGIN_ID,
                    "No workbook loader is set.")); //$NON-NLS-1$

        setWorkbook(workbookLoader.loadWorkbook(storage, encryptionHandler,
                monitor));
    }

    IStorage createStorage() {
        String tempFile = Core.getIdFactory().createId()
                + MindMapUI.FILE_EXT_XMIND_TEMP;
        String tempLocation = Core.getWorkspace().getTempDir(
                SUBDIR_WORKBOOK + "/" + tempFile); //$NON-NLS-1$
        File tempDir = new File(tempLocation);
        IStorage storage = new DirectoryStorage(tempDir);
        return storage;
    }

    public boolean isSaveable() {
        return workbook != null && workbookSaver != null;
    }

    public void saveWorkbook(IProgressMonitor monitor) throws IOException,
            CoreException, org.eclipse.core.runtime.CoreException {
        if (workbook == null)
            throw new org.eclipse.core.runtime.CoreException(new Status(
                    IStatus.ERROR, MindMapUIPlugin.PLUGIN_ID,
                    "No workbook to save.")); //$NON-NLS-1$
        if (workbookSaver == null)
            throw new org.eclipse.core.runtime.CoreException(new Status(
                    IStatus.ERROR, MindMapUIPlugin.PLUGIN_ID,
                    "No workbook saver is set.")); //$NON-NLS-1$

        workbookSaver.save(monitor, workbook);
    }

    public void propertyChange(PropertyChangeEvent event) {
        if (commandStack != null) {
            if (PrefConstants.UNDO_LIMIT.equals(event.getProperty())) {
                commandStack.setUndoLimit(Math.max((Integer) event
                        .getNewValue(), 1));
            }
        }
    }

    public void partActivated(IWorkbenchPart part) {
        if (referrers != null && referrers.remove(part)) {
            referrers.add(0, (IEditorPart) part);
        }
    }

    public void partBroughtToTop(IWorkbenchPart part) {
        // do nothing
    }

    public void partClosed(IWorkbenchPart part) {
        // do nothing
    }

    public void partDeactivated(IWorkbenchPart part) {
        // do nothing
    }

    public void partOpened(IWorkbenchPart part) {
        // do nothing
    }
}