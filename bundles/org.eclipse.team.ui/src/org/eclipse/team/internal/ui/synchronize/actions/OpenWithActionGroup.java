/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ui.synchronize.actions;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.team.ui.synchronize.*;
import org.eclipse.team.ui.synchronize.views.SyncInfoSetContentProvider;
import org.eclipse.ui.actions.ActionGroup;
import org.eclipse.ui.actions.OpenWithMenu;
import org.eclipse.ui.views.navigator.ResourceNavigatorMessages;

/**
 * This is the action group for the open actions. It contains open
 * actions for 
 */
public class OpenWithActionGroup extends ActionGroup {

	private OpenFileInSystemEditorAction openFileAction;
	private OpenInCompareAction openInCompareAction;
	private TeamSubscriberParticipantPage page;
	private ISynchronizeView view;
	private ISynchronizeParticipant participant;

	public OpenWithActionGroup(ISynchronizeView part, ISynchronizeParticipant participant) {
		this.participant = participant;
		this.view = part;
		makeActions();
	}

	protected void makeActions() {
		openFileAction = new OpenFileInSystemEditorAction(view.getSite().getPage());
		openInCompareAction = new OpenInCompareAction(view, participant);		
	}

	public void fillContextMenu(IMenuManager menu) {
		fillOpenWithMenu(menu, (IStructuredSelection)view.getSite().getPage().getSelection());
	}

	/**
	 * Adds the OpenWith submenu to the context menu.
	 * 
	 * @param menu the context menu
	 * @param selection the current selection
	 */
	private void fillOpenWithMenu(IMenuManager menu, IStructuredSelection selection) {

		// Only supported if exactly one file is selected.
		if (selection == null || selection.size() != 1)
			return;
		Object element = selection.getFirstElement();
		IResource resource = getResource(element);
		if (!(resource instanceof IFile)) {
			return;
		}
				
		menu.add(openInCompareAction);
		
		if(!((resource.exists()))) {
			return;
		}
		
		openFileAction.selectionChanged(selection);
		menu.add(openFileAction);
		
		MenuManager submenu =
			new MenuManager(ResourceNavigatorMessages.getString("ResourceNavigator.openWith")); //$NON-NLS-1$
		submenu.add(new OpenWithMenu(view.getSite().getPage(), (IFile) resource));
		menu.add(submenu);
	}

	/**
	 * Runs the default action (open file).
	 */
	public void runDefaultAction(IStructuredSelection selection) {
		Object element = selection.getFirstElement();
		if (element instanceof IFile) {
			openFileAction.selectionChanged(selection);
			openFileAction.run();
		}
	}
	
	private IResource getResource(Object obj) {
		return SyncInfoSetContentProvider.getResource(obj);
	}

	public void openInCompareEditor() {
		openInCompareAction.run();		
	}
}