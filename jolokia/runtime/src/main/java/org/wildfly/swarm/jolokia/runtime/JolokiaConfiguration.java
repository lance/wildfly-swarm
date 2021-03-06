/**
 * Copyright 2015 Red Hat, Inc, and individual contributors.
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
package org.wildfly.swarm.jolokia.runtime;

import java.util.ArrayList;
import java.util.List;

import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.wildfly.swarm.Swarm;
import org.wildfly.swarm.container.runtime.AbstractServerConfiguration;
import org.wildfly.swarm.jolokia.JolokiaFraction;
import org.wildfly.swarm.undertow.WARArchive;

/**
 * @author Bob McWhirter
 */
public class JolokiaConfiguration extends AbstractServerConfiguration<JolokiaFraction> {

    public JolokiaConfiguration() {
        super(JolokiaFraction.class);
    }

    @Override
    public JolokiaFraction defaultFraction() {
        System.err.println("create default fraction for Jolokia");
        return new JolokiaFraction();
    }

    @Override
    public List<Archive> getImplicitDeployments(JolokiaFraction fraction) throws Exception {
        List<Archive> list = new ArrayList<>();
        JavaArchive war = null;

        war = Swarm.artifact("org.jolokia:jolokia-war:war:*");
        war.as(WARArchive.class).setContextRoot(fraction.context());
        list.add(war);

        return list;
    }
}
