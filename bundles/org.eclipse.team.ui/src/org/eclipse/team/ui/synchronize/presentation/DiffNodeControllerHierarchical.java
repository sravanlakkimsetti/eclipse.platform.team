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
package org.eclipse.team.ui.synchronize.presentation;

import java.util.*;

import org.eclipse.compare.structuremergeviewer.*;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.*;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ViewerSorter;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.widgets.Control;
import org.eclipse.team.core.ITeamStatus;
import org.eclipse.team.core.synchronize.*;
import org.eclipse.team.internal.core.Assert;
import org.eclipse.team.internal.core.TeamPlugin;
import org.eclipse.team.internal.ui.TeamUIPlugin;
import org.eclipse.team.internal.ui.Utils;

/**
 * An input that can be used with both {@link } and 
 * {@link }. The
 * job of this input is to create the logical model of the contents of the
 * sync set for displaying to the user. The created logical model must diff
 * nodes.
 * <p>
 * 1. First, prepareInput is called to initialize the model with the given sync
 * set. Building the model occurs in the ui thread.
 * 2. The input must react to changes in the sync set and adjust its diff node
 * model then update the viewer. In effect mediating between the sync set
 * changes and the model shown to the user. This happens in the ui thread.
 * </p>
 * NOT ON DEMAND - model is created then maintained!
 * @since 3.0
 */
public class DiffNodeControllerHierarchical extends DiffNodeController implements ISyncInfoSetChangeListener {

	// During updates we keep track of the parent elements that need their
	// labels updated. This is required to support displaying information in a 
	// parent label that is dependant on the state of its children. For example,
	// showing conflict markers on folders if it contains child conflicts.
	private Set pendingLabelUpdates = new HashSet();
	
	// Map from resources to model objects. This allows effecient lookup
	// of model objects based on changes occuring to resources.
	private Map resourceMap = Collections.synchronizedMap(new HashMap());
	
	// The viewer this input is being displayed in
	private AbstractTreeViewer viewer;
	
	// Flasg to indicate if tree control should be updated while
	// building the model.
	private boolean refreshViewer;
	
	private RootDiffNode root;
	
	private SyncInfoTree set;

	private class RootDiffNode extends UnchangedResourceDiffNode {
		public RootDiffNode() {
			super(null, ResourcesPlugin.getWorkspace().getRoot());
		}
		public void fireChanges() {
			fireChange();
		}
	}
	
	/**
	 * Create an input based on the provide sync set. The input is not initialized
	 * until <code>prepareInput</code> is called. 
	 * 
	 * @param set the sync set used as the basis for the model created by this input.
	 */
	public DiffNodeControllerHierarchical(SyncInfoTree set) {
		Assert.isNotNull(set);
		this.root = new RootDiffNode();
		this.set = set;
	}

	/**
	 * Return the model object (i.e. an instance of <code>SyncInfoDiffNode</code>
	 * or one of its subclasses) for the given IResource.
	 * @param resource
	 *            the resource
	 * @return the <code>SyncInfoDiffNode</code> for the given resource
	 */
	protected DiffNode getModelObject(IResource resource) {
		return (DiffNode) resourceMap.get(resource);
	}

	/**
	 * Return the <code>AbstractTreeViewer</code> asociated with this content
	 * provider or <code>null</code> if the viewer is not of the proper type.
	 * @return
	 */
	public AbstractTreeViewer getTreeViewer() {
		return viewer;
	}

	public void setViewer(AbstractTreeViewer viewer) {
		this.viewer = viewer;
	}

	public ViewerSorter getViewerSorter() {
		return new SyncInfoDiffNodeSorter();
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.compare.structuremergeviewer.DiffContainer#hasChildren()
	 */
	public boolean hasChildren() {
		// This is required to allow the sync framework to be used in wizards
		// where the input is not populated until after the compare input is
		// created
		// (i.e. the compare input will only create the diff viewer if the
		// input has children
		return true;
	}

	/**
	 * Builds the viewer model based on the contents of the sync set.
	 */
	public DiffNode prepareInput(IProgressMonitor monitor) {
		try {
			// Connect to the sync set which will register us as a listener and give us a reset event
			// in a background thread
			getSyncInfoTree().connect(this, monitor);
			return getRoot();
		} catch (CoreException e) {
			TeamPlugin.log(e);
		}
		return null;
	}
	
	/**
	 * Dispose of the builder
	 */
	public void dispose() {
		resourceMap.clear();
		getSyncInfoTree().removeSyncSetChangedListener(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.eclipse.team.ccvs.syncviews.views.ISyncSetChangedListener#syncSetChanged()
	 */
	public void syncInfoChanged(final ISyncInfoSetChangeEvent event, IProgressMonitor monitor) {
		if (! (event instanceof ISyncInfoTreeChangeEvent)) {
			reset();
		} else {
			final Control ctrl = viewer.getControl();
			if (ctrl != null && !ctrl.isDisposed()) {
				ctrl.getDisplay().syncExec(new Runnable() {
					public void run() {
						if (!ctrl.isDisposed()) {
							BusyIndicator.showWhile(ctrl.getDisplay(), new Runnable() {
								public void run() {
									handleChanges((ISyncInfoTreeChangeEvent)event);
									getRoot().fireChanges();
								}
							});
						}
					}
				});
			}
		}
	}

	/**
	 * For each node create children based on the contents of
	 * @param node
	 * @return
	 */
	protected IDiffElement[] buildModelObjects(DiffNode node) {
		IDiffElement[] children = createModelObjects(node);
		for (int i = 0; i < children.length; i++) {
			IDiffElement element = children[i];
			if (element instanceof DiffNode) {
				buildModelObjects((DiffNode) element);
			}
		}
		return children;
	}

	/**
	 * Create the
	 * @param container
	 * @return
	 */
	protected IDiffElement[] createModelObjects(DiffNode container) {
		IResource resource = null;
		if (container == getRoot()) {
			resource = ResourcesPlugin.getWorkspace().getRoot();
		} else {
			resource = (IResource)Utils.getAdapter(container, IResource.class);
		}
		if(resource != null) {
			SyncInfoTree infoTree = getSyncInfoTree();
			IResource[] children = infoTree.members(resource);
			DiffNode[] nodes = new DiffNode[children.length];
			for (int i = 0; i < children.length; i++) {
				nodes[i] = createModelObject(container, children[i]);
			}
			return nodes;	
		}
		return new IDiffElement[0];
	}

	protected DiffNode createModelObject(DiffNode parent, IResource resource) {
		SyncInfo info = getSyncInfoTree().getSyncInfo(resource);
		DiffNode newNode;
		if(info != null) {
			newNode = new SyncInfoDiffNode(parent, info);
		} else {
			newNode = new UnchangedResourceDiffNode(parent, resource);
		}
		addToViewer(newNode);
		return newNode;
	}

	/**
	 * Clear the model objects from the diff tree, cleaning up any cached state
	 * (such as resource to model object map). This method recurses deeply on
	 * the tree to allow the cleanup of any cached state for the children as
	 * well.
	 * @param node
	 *            the root node
	 */
	protected void clearModelObjects(DiffNode node) {
		IDiffElement[] children = node.getChildren();
		for (int i = 0; i < children.length; i++) {
			IDiffElement element = children[i];
			if (element instanceof DiffNode) {
				clearModelObjects((DiffNode) element);
			}
		}
		IResource resource = (IResource)Utils.getAdapter(node, IResource.class);
		if (resource != null) {
			unassociateDiffNode(resource);
		}
		IDiffContainer parent = node.getParent();
		if (parent != null) {
			parent.removeToRoot(node);
		}
	}

	/**
	 * Invokes <code>getModelObject(Object)</code> on an array of resources.
	 * @param resources
	 *            the resources
	 * @return the model objects for the resources
	 */
	protected Object[] getModelObjects(IResource[] resources) {
		Object[] result = new Object[resources.length];
		for (int i = 0; i < resources.length; i++) {
			result[i] = getModelObject(resources[i]);
		}
		return result;
	}

	protected void associateDiffNode(DiffNode node) {
		if(node instanceof IAdaptable) {
			IResource resource = (IResource)((IAdaptable)node).getAdapter(IResource.class);
			if(resource != null) {
				resourceMap.put(resource, node);
			}
		}
	}

	protected void unassociateDiffNode(IResource resource) {
		resourceMap.remove(resource);
	}

	/**
	 * Handle the changes made to the viewer's <code>SyncInfoSet</code>.
	 * This method delegates the changes to the three methods <code>handleResourceChanges(ISyncInfoSetChangeEvent)</code>,
	 * <code>handleResourceRemovals(ISyncInfoSetChangeEvent)</code> and
	 * <code>handleResourceAdditions(ISyncInfoSetChangeEvent)</code>.
	 * @param event
	 *            the event containing the changed resourcses.
	 */
	protected void handleChanges(ISyncInfoTreeChangeEvent event) {
		try {
			viewer.getControl().setRedraw(false);
			handleResourceChanges(event);
			handleResourceRemovals(event);
			handleResourceAdditions(event);
			firePendingLabelUpdates();
		} finally {
			viewer.getControl().setRedraw(true);
		}
	}

	/**
	 * Update the viewer for the sync set additions in the provided event. This
	 * method is invoked by <code>handleChanges(ISyncInfoSetChangeEvent)</code>.
	 * Subclasses may override.
	 * @param event
	 */
	protected void handleResourceAdditions(ISyncInfoTreeChangeEvent event) {
		IResource[] added = event.getAddedSubtreeRoots();
		addResources(added);
	}

	/**
	 * Update the viewer for the sync set changes in the provided event. This
	 * method is invoked by <code>handleChanges(ISyncInfoSetChangeEvent)</code>.
	 * Subclasses may override.
	 * @param event
	 */
	protected void handleResourceChanges(ISyncInfoTreeChangeEvent event) {
		// Refresh the viewer for each changed resource
		SyncInfo[] infos = event.getChangedResources();
		for (int i = 0; i < infos.length; i++) {
			SyncInfo info = infos[i];
			IResource local = info.getLocal();
			DiffNode diffNode = getModelObject(local);
			// If a sync info diff node already exists then just update
			// it, otherwise remove the old diff node and create a new
			// sub-tree.
			if (diffNode != null) {
				handleChange(diffNode, info);
			}
		}
	}
	
	/**
	 * Handle the change for the existing diff node. The diff node
	 * should be changed to have the given sync info
	 * @param diffNode the diff node to be changed
	 * @param info the new sync info for the diff node
	 */
	protected void handleChange(DiffNode diffNode, SyncInfo info) {
		IResource local = info.getLocal();
		// TODO: Get any additional sync bits
		if(diffNode instanceof SyncInfoDiffNode) {
			boolean wasConflict = isConflicting(diffNode);
			// The update preserves any of the additional sync info bits
			((SyncInfoDiffNode)diffNode).update(info);
			boolean isConflict = isConflicting(diffNode);
			updateLabel(diffNode);
			if (wasConflict && !isConflict) {
				conflictRemoved(diffNode);
			} else if (!wasConflict && isConflict) {
				setParentConflict(diffNode);
			}
		} else {
			removeFromViewer(local);
			addResources(new IResource[] {local});
		}
		// TODO: set any additional sync info bits
	}

	protected boolean isConflicting(DiffNode diffNode) {
		return (diffNode.getKind() & SyncInfo.DIRECTION_MASK) == SyncInfo.CONFLICTING;
	}

	/**
	 * Update the viewer for the sync set removals in the provided event. This
	 * method is invoked by <code>handleChanges(ISyncInfoSetChangeEvent)</code>.
	 * Subclasses may override.
	 * @param event
	 */
	protected void handleResourceRemovals(ISyncInfoTreeChangeEvent event) {
		// Remove the removed subtrees
		IResource[] removedRoots = event.getRemovedSubtreeRoots();
		for (int i = 0; i < removedRoots.length; i++) {
			removeFromViewer(removedRoots[i]);
		}
		// We have to look for folders that may no longer be in the set
		// (i.e. are in-sync) but still have descendants in the set
		IResource[] removedResources = event.getRemovedResources();
		for (int i = 0; i < removedResources.length; i++) {
			IResource resource = removedResources[i];
			if (resource.getType() != IResource.FILE) {
				DiffNode node = getModelObject(resource);
				if (node != null) {
					removeFromViewer(resource);
					addResources(new IResource[] {resource});
				}
			}
		}
	}

	protected void reset() {
		try {
			refreshViewer = false;
			
			// Clear existing model, but keep the root node
			resourceMap.clear();
			clearModelObjects(getRoot());
			// remove all from tree viewer
			IDiffElement[] elements = getRoot().getChildren();
			for (int i = 0; i < elements.length; i++) {
				viewer.remove(elements[i]);
			}
			
			// Rebuild the model
			associateDiffNode(getRoot());
			buildModelObjects(getRoot());
			
			// Notify listeners that model has changed
			getRoot().fireChanges();
		} finally {
			refreshViewer = true;
		}
		TeamUIPlugin.getStandardDisplay().asyncExec(new Runnable() {
			public void run() {
				if (viewer != null && !viewer.getControl().isDisposed()) {
					viewer.refresh();
				}
			}
		});
	}

	protected RootDiffNode getRoot() {
		return root;
	}
	
	protected SyncInfoTree getSyncInfoTree() {
		return set;
	}

	/**
	 * Remove any traces of the resource and any of it's descendants in the
	 * hiearchy defined by the content provider from the content provider and
	 * the viewer it is associated with.
	 * @param resource
	 */
	protected void removeFromViewer(IResource resource) {
		DiffNode node = getModelObject(resource);
		if (node == null) return;
		boolean wasConflict = isConflicting(node);
		clearModelObjects(node);
		if (wasConflict) {
			conflictRemoved(node);
		}
		if (canUpdateViewer()) {
			AbstractTreeViewer tree = getTreeViewer();
			tree.remove(node);
		}
	}

	protected void addToViewer(DiffNode node) {
		associateDiffNode(node);
		if (isConflicting(node)) {
			setParentConflict(node);
		}
		if (canUpdateViewer()) {
			AbstractTreeViewer tree = getTreeViewer();
			tree.add(node.getParent(), node);
		}
	}

	protected void addResources(IResource[] added) {
		for (int i = 0; i < added.length; i++) {
			IResource resource = added[i];
			DiffNode node = getModelObject(resource);
			if (node != null) {
				// Somehow the node exists. Remove it and read it to ensure
				// what is shown matches the contents of the sync set
				removeFromViewer(resource);
			}
			// Build the sub-tree rooted at this node
			DiffNode parent = getModelObject(resource.getParent());
			if (parent != null) {
				node = createModelObject(parent, resource);
				buildModelObjects(node);
			}
		}
	}
	
	/**
	 * @param tree
	 * @return
	 */
	private boolean canUpdateViewer() {
		return refreshViewer && getTreeViewer() != null;
	}

	/**
	 * Forces the viewer to update the labels for parents whose children have
	 * changed during this round of sync set changes.
	 */
	protected void firePendingLabelUpdates() {
		try {
			if (canUpdateViewer()) {
				AbstractTreeViewer tree = getTreeViewer();
				tree.update(pendingLabelUpdates.toArray(new Object[pendingLabelUpdates.size()]), null);
			}
		} finally {
			pendingLabelUpdates.clear();
		}
	}

	/**
	 * Forces the viewer to update the labels for parents of this element. This
	 * can be useful when parents labels include information about their
	 * children that needs updating when a child changes.
	 * <p>
	 * This method should only be called while processing sync set changes.
	 * Changed parents are accumulated and updated at the end of the change
	 * processing
	 */
	protected void setParentConflict(DiffNode diffNode) {
		propogateFlag(diffNode, AdaptableDiffNode.PROPOGATED_CONFLICT);
	}
	
	/**
	 * The given node has changed from a conflict. The PARENT_OF_CONFLICT
	 * flag needs to be recalculated for the nodes parents
	 * @param diffNode the node whose conflict state change
	 */
	protected void conflictRemoved(DiffNode diffNode) {
		recalculateParentFlag(diffNode, AdaptableDiffNode.PROPOGATED_CONFLICT);
	}
	
	public void setBusy(DiffNode[] nodes) {
		for (int i = 0; i < nodes.length; i++) {
			DiffNode node = nodes[i];
			propogateFlag(node, AdaptableDiffNode.BUSY);
		}
	}
	
	public void clearBusy(DiffNode[] nodes) {
		for (int i = 0; i < nodes.length; i++) {
			DiffNode node = nodes[i];
			recalculateParentFlag(node, AdaptableDiffNode.BUSY);
		}
	}
	
	/**
	 * Add the given flag to the sync kind of the diff node and
	 * all of its ancestors.
	 * @param diffNode the diff node
	 * @param flag the flag to be added to the diff node and its ancestors
	 */
	private void propogateFlag(DiffNode diffNode, int flag) {
		addFlag(diffNode, flag);
		DiffNode parent = (DiffNode)diffNode.getParent();
		while (parent != null) {
			if (hasFlag(parent, flag)) return;
			addFlag(parent, flag);
			parent = (DiffNode)parent.getParent();
		}
	}

	/**
	 * Return whether the given diff node has the one-bit flag set
	 * in its sync kind.
	 * @param diffNode the diff node
	 * @param flag the one-bit flag
	 * @return <code>true</code> if the flag is set in the diff node's sync kind
	 */
	private boolean hasFlag(DiffNode diffNode, int flag) {
		return (((AdaptableDiffNode)diffNode).getFlags() & flag) != 0;
	}
	
	private void addFlag(DiffNode diffNode, int flag) {
		((AdaptableDiffNode)diffNode).addFlag(flag);
		updateLabel(diffNode);
	}
	
	private void removeFlag(DiffNode diffNode, int flag) {
		((AdaptableDiffNode)diffNode).removeFlag(flag);
		updateLabel(diffNode);
	}
	
	private void recalculateParentFlag(DiffNode diffNode, int flag) {
		removeFlag(diffNode, flag);
		DiffNode parent = (DiffNode)diffNode.getParent();
		if (parent != null) {
			// If the parent doesn't have the tag, no recalculation is required
			// Also, if the parent still has a child with the tag, no recalculation is needed
			if (hasFlag(parent, flag) && !hasChildWithFlag(parent, flag)) {
				// The parent no longer has the flag so propogate the reclaculation
				recalculateParentFlag(parent, flag);
			}
		}
	}
	
	/**
	 * @param parent
	 * @param flag
	 * @return
	 */
	private boolean hasChildWithFlag(DiffNode parent, int flag) {
		IDiffElement[] childen = parent.getChildren();
		for (int i = 0; i < childen.length; i++) {
			IDiffElement element = childen[i];
			if (hasFlag((DiffNode)element, flag)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Update the label of the given diff node. Diff nodes
	 * are accumulated and updated in a single call.
	 * @param diffNode the diff node to be updated
	 */
	protected void updateLabel(DiffNode diffNode) {
		pendingLabelUpdates.add(diffNode);
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.core.subscribers.ISyncInfoSetChangeListener#syncInfoSetReset(org.eclipse.team.core.subscribers.SyncInfoSet, org.eclipse.core.runtime.IProgressMonitor)
	 */
	public void syncInfoSetReset(SyncInfoSet set, IProgressMonitor monitor) {
		reset();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.team.core.subscribers.ISyncInfoSetChangeListener#syncInfoSetError(org.eclipse.team.core.subscribers.SyncInfoSet, org.eclipse.team.core.ITeamStatus[], org.eclipse.core.runtime.IProgressMonitor)
	 */
	public void syncInfoSetErrors(SyncInfoSet set, ITeamStatus[] errors, IProgressMonitor monitor) {
		// TODO Auto-generated method stub
	}

	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.synchronize.viewers.DiffNodeController#getInput()
	 */
	public DiffNode getInput() {
		return getRoot();
	}
}
