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
package org.wildfly.swarm.arquillian.adapter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.arquillian.protocol.servlet.ServletMethodExecutor;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.jboss.shrinkwrap.resolver.api.maven.ConfigurableMavenResolverSystem;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenChecksumPolicy;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenRemoteRepositories;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenRemoteRepository;
import org.jboss.shrinkwrap.resolver.api.maven.repository.MavenUpdatePolicy;
import org.wildfly.swarm.tools.BuildTool;

/**
 * @author Bob McWhirter
 */
public class WildFlySwarmContainer implements DeployableContainer<WildFlySwarmContainerConfiguration> {

    private Class<?> testClass;

    private List<String> requestedMavenArtifacts;

    private Process process;

    private IOBridge stdout;
    private IOBridge stderr;

    @Override
    public Class<WildFlySwarmContainerConfiguration> getConfigurationClass() {
        return WildFlySwarmContainerConfiguration.class;
    }

    @Override
    public void setup(WildFlySwarmContainerConfiguration config) {
    }

    @Override
    public void start() throws LifecycleException {
    }

    @Override
    public void stop() throws LifecycleException {
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 3.0");
    }

    public void setTestClass(Class<?> testClass) {
        this.testClass = testClass;
    }

    public void setRequestedMavenArtifacts(List<String> artifacts) {
        this.requestedMavenArtifacts = artifacts;
    }

    public boolean isContainerFactory(Class<?> cls) {
        if (cls.getName().equals("org.wildfly.swarm.ContainerFactory")) {
            return true;
        }

        for (Class<?> interf : cls.getInterfaces()) {
            if (isContainerFactory(interf)) {
                return true;
            }
        }

        if (cls.getSuperclass() != null) {
            return isContainerFactory(cls.getSuperclass());
        }

        return false;
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {

        /*
        System.err.println( ">>> CORE" );
        for (Map.Entry<ArchivePath, Node> each : archive.getContent().entrySet()) {
            System.err.println("-> " + each.getKey());
        }
        System.err.println( "<<< CORE" );
        */


        //System.err.println("is factory: " + isContainerFactory(this.testClass));
        if (isContainerFactory(this.testClass)) {
            archive.as(JavaArchive.class).addAsServiceProvider("org.wildfly.swarm.ContainerFactory", this.testClass.getName());
            archive.as(JavaArchive.class).addClass(this.testClass);
        }

        BuildTool tool = new BuildTool();
        tool.projectArchive(archive);

        MavenRemoteRepository jbossPublic = MavenRemoteRepositories.createRemoteRepository("jboss-public-repository-group", "http://repository.jboss.org/nexus/content/groups/public/", "default");
        jbossPublic.setChecksumPolicy(MavenChecksumPolicy.CHECKSUM_POLICY_IGNORE);
        jbossPublic.setUpdatePolicy(MavenUpdatePolicy.UPDATE_POLICY_NEVER);

        ConfigurableMavenResolverSystem resolver = Maven.configureResolver()
                .withMavenCentralRepo(true)
                .withRemoteRepo(jbossPublic);

        tool.artifactResolvingHelper(new ShrinkwrapArtifactResolvingHelper(resolver));

        boolean hasRequestedArtifacts = this.requestedMavenArtifacts != null && this.requestedMavenArtifacts.size() > 0;

        if (!hasRequestedArtifacts) {
            MavenResolvedArtifact[] deps = resolver.loadPomFromFile("pom.xml").importRuntimeAndTestDependencies().resolve().withTransitivity().asResolvedArtifact();

            for (MavenResolvedArtifact dep : deps) {
                MavenCoordinate coord = dep.getCoordinate();
                tool.dependency(dep.getScope().name(), coord.getGroupId(), coord.getArtifactId(), coord.getVersion(), coord.getPackaging().getExtension(), coord.getClassifier(), dep.asFile());
            }
        } else {
            for (String requestedDep : this.requestedMavenArtifacts) {
                MavenResolvedArtifact[] deps = resolver.loadPomFromFile("pom.xml").resolve(requestedDep).withTransitivity().asResolvedArtifact();

                for (MavenResolvedArtifact dep : deps) {
                    MavenCoordinate coord = dep.getCoordinate();
                    tool.dependency(dep.getScope().name(), coord.getGroupId(), coord.getArtifactId(), coord.getVersion(), coord.getPackaging().getExtension(), coord.getClassifier(), dep.asFile());
                }
            }
        }

        try {

            Path java = findJava();
            if (java == null) {
                throw new DeploymentException("Unable to locate `java` binary");
            }

            Archive<?> wrapped = tool.build();

            /*
            wrapped.as(ZipExporter.class).exportTo(new File("test.jar"), true);

            for (Map.Entry<ArchivePath, Node> each : wrapped.getContent().entrySet()) {
                System.err.println("-> " + each.getKey());
            }
            */

            File executable = File.createTempFile("arquillian", "-swarm.jar");
            wrapped.as(ZipExporter.class).exportTo(executable, true);
            executable.deleteOnExit();

            List<String> cli = new ArrayList<>();

            cli.add(java.toString());

            cli.add("-Djava.net.preferIPv4Stack=true");

            Enumeration<?> names = System.getProperties().propertyNames();

            while (names.hasMoreElements()) {
                String key = (String) names.nextElement();
                if (key.startsWith("jboss") || key.startsWith("swarm") || key.startsWith("wildfly") || key.startsWith( "maven" )) {
                    String value = System.getProperty(key);
                    cli.add("-D" + key + "=" + value);
                }
            }

            cli.add("-jar");
            cli.add(executable.getAbsolutePath());


            this.process = Runtime.getRuntime().exec(cli.toArray(new String[cli.size()]));

            CountDownLatch latch = new CountDownLatch(1);
            this.stdout = new IOBridge("out", latch, process.getInputStream(), System.out);
            this.stderr = new IOBridge("err", latch, process.getErrorStream(), System.err);

            this.process.getOutputStream().close();

            new Thread(stdout).start();
            new Thread(stderr).start();

            ProtocolMetaData metaData = new ProtocolMetaData();
            HTTPContext context = new HTTPContext("localhost", 8080);
            context.add(new Servlet(ServletMethodExecutor.ARQUILLIAN_SERVLET_NAME, "/"));
            metaData.addContext(context);
            latch.await(2, TimeUnit.MINUTES);
            if ( ! this.process.isAlive() ) {
                throw new DeploymentException( "Process failed to start" );
            }
            if ( this.stdout.getError() != null ) {
                throw new DeploymentException( "Error starting process", this.stdout.getError() );
            }
            if ( this.stderr.getError() != null ) {
                throw new DeploymentException( "Error starting process", this.stderr.getError() );
            }
            return metaData;
        } catch (Exception e) {
            throw new DeploymentException(e.getMessage(), e);
        }
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        try {
            try {
                this.stdout.close();
            } catch (IOException e) {
                // ignore
            }
            try {
                this.stderr.close();
            } catch (IOException e) {
                // ignore
            }

            this.process.destroy();
            this.process.waitFor(10, TimeUnit.SECONDS);
            this.process.destroyForcibly();
            this.process.waitFor(10, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
    }

    private Path findJava() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            return null;
        }

        Path binDir = FileSystems.getDefault().getPath(javaHome, "bin");

        Path java = binDir.resolve("java.exe");
        if (java.toFile().exists()) {
            return java;
        }

        java = binDir.resolve("java");
        if (java.toFile().exists()) {
            return java;
        }

        return null;
    }

    private static class IOBridge implements Runnable {

        private final String name;

        private final InputStream in;

        private final OutputStream out;

        private Exception error;

        private final CountDownLatch latch;

        public IOBridge(String name, CountDownLatch latch, InputStream in, OutputStream out) {
            this.name = name;
            this.in = in;
            this.out = out;
            this.latch = latch;
        }

        public Exception getError() {
            return this.error;
        }

        @Override
        public void run() {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String line = null;
            try {
                while ((line = reader.readLine()) != null) {
                    processLine(line);
                }
            } catch (IOException e) {
                this.error = e;
                this.latch.countDown();
            }
        }

        protected void processLine(String line) throws IOException {
            out.write(line.getBytes());
            out.write('\n');
            if (line.contains("WFLYSRV0010")) {
                this.latch.countDown();
            }
        }

        public void close() throws IOException {
            this.in.close();
        }
    }
}
