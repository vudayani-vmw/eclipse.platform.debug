/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ui.internal.console;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.activities.WorkbenchActivityHelper;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsoleConstants;
import org.eclipse.ui.console.IConsoleFactory;

/**
 * @since 3.1
 */
public class OpenConsoleAction extends Action implements IMenuCreator {

    private ConsoleFactoryExtension[] fFactoryExtensions;
    private Menu fMenu;

    public OpenConsoleAction() {
        fFactoryExtensions = ConsolePlugin.getDefault().getConsoleManager().getConsoleFactoryExtensions();
		setText("Open Console");
		setToolTipText("Open Console"); 
		setImageDescriptor(ConsolePluginImages.getImageDescriptor(IConsoleConstants.IMG_VIEW_CONSOLE));
		setMenuCreator(this);
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.jface.action.IMenuCreator#dispose()
     */
    public void dispose() {
        fFactoryExtensions = null;
    }

    /* (non-Javadoc)
     * @see org.eclipse.jface.action.IMenuCreator#getMenu(org.eclipse.swt.widgets.Control)
     */
    public Menu getMenu(Control parent) {
        if (fMenu != null) {
            fMenu.dispose();
        }
        
        fMenu= new Menu(parent);
        for (int i = 0; i < fFactoryExtensions.length; i++) {
            ConsoleFactoryExtension extension = fFactoryExtensions[i];
            if (!WorkbenchActivityHelper.filterItem(extension) && extension.isEnabled()) {
                String label = extension.getLabel();
                ImageDescriptor image = extension.getImageDescriptor();
                addActionToMenu(fMenu, new ConsoleFactoryAction(label, image, extension));
            }
        }
        return fMenu;
    }
    
	protected void addActionToMenu(Menu parent, Action action) {
		ActionContributionItem item= new ActionContributionItem(action);
		item.fill(parent, -1);
	}

    /* (non-Javadoc)
     * @see org.eclipse.jface.action.IMenuCreator#getMenu(org.eclipse.swt.widgets.Menu)
     */
    public Menu getMenu(Menu parent) {
        // TODO Auto-generated method stub
        return null;
    }
    
    private class ConsoleFactoryAction extends Action {
        
        private ConsoleFactoryExtension fConfig;
        private IConsoleFactory fFactory;

        public ConsoleFactoryAction(String label, ImageDescriptor image, ConsoleFactoryExtension extension) {
            setText(label);
            if (image != null) {
                setImageDescriptor(image);
            }
            fConfig = extension;
        }
        
        
        /* (non-Javadoc)
         * @see org.eclipse.jface.action.IAction#run()
         */
        public void run() {
            try {
                if (fFactory == null) {
                    fFactory = fConfig.createFactory();
                }
                
                fFactory.openConsole();
            } catch (CoreException e) {
                ConsolePlugin.log(e);
            }
        }
        
        /* (non-Javadoc)
         * @see org.eclipse.jface.action.IAction#runWithEvent(org.eclipse.swt.widgets.Event)
         */
        public void runWithEvent(Event event) {
            run();
        }
    }
}
