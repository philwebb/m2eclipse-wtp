/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.wtp;


/**
 * This factory creates IProjectConfiguratorDelegate based on Maven projects packaging.
 *
 * @author Fred Bricon
 */
//XXX See if we could refactor this to do JEEPackaging.createProjectConfiguratorDelegate() instead.
class ProjectConfiguratorDelegateFactory {

  private ProjectConfiguratorDelegateFactory() {
    //We don't need to instantiate this class
  }


  /**
   * IProjectConfiguratorDelegate factory method.
   * @param packaging supported values are war, ejb, ear.
   * @return a new instance of IProjectConfiguratorDelegate or null if packaging is not supported.
   */
  static IProjectConfiguratorDelegate getProjectConfiguratorDelegate(String packaging){
    JEEPackaging mvnPackaging = JEEPackaging.getValue(packaging);

    switch(mvnPackaging) {
      case WAR_OVERLAY:
        return new WarOverlayProjectConfiguratorDelegate();
      case WAR:
        return new WebProjectConfiguratorDelegate();
      case EJB:
        return new EjbProjectConfiguratorDelegate();
      case EAR:
        return new EarProjectConfiguratorDelegate();
      case RAR:
        return new ConnectorProjectConfiguratorDelegate();
      default :
        return null;
    }

  }

}
