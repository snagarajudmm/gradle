/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.integtests.resolve.maven

import org.gradle.integtests.fixtures.GradleMetadataResolveRunner
import org.gradle.integtests.fixtures.RequiredFeature
import org.gradle.integtests.fixtures.RequiredFeatures
import org.gradle.integtests.resolve.AbstractModuleDependencyResolveTest

@RequiredFeatures(
    @RequiredFeature(feature = GradleMetadataResolveRunner.REPOSITORY_TYPE, value = "maven")
)
class MavenDependencyResolveIntegrationTest extends AbstractModuleDependencyResolveTest {

    String getRootProjectName() { 'testproject' }

    def "dependency includes main artifact and runtime dependencies of referenced module"() {
        given:
        repository {
            'org.gradle:other:preview-1'()
            'org.gradle:test:1.45' {
                dependsOn 'org.gradle:other:preview-1'
                withModule {
                    artifact(classifier: 'classifier') // ignored
                }
            }
        }

        and:
        buildFile << """
group = 'org.gradle'
version = '1.0'
dependencies {
    conf "org.gradle:test:1.45"
}
"""

        repositoryInteractions {
            'org.gradle:test:1.45' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org.gradle:other:preview-1' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }

        expect:
        succeeds "checkDep"
        resolve.expectGraph {
            root(':', 'org.gradle:testproject:1.0') {
                module("org.gradle:test:1.45") {
                    module("org.gradle:other:preview-1")
                }
            }
        }
    }

    def "dependency that references a classifier includes the matching artifact only plus the runtime dependencies of referenced module"() {
        given:
        repository {
            'org.gradle' {
                'other' {
                    'preview-1'()
                }
                'test' {
                    '1.45' {
                        dependsOn 'org.gradle:other:preview-1'
                        withModule {
                            artifact(classifier: 'classifier')
                            artifact(classifier: 'some-other') // ignored
                        }
                    }
                }
            }
        }

        and:
        buildFile << """
group = 'org.gradle'
version = '1.0'
dependencies {
    conf "org.gradle:test:1.45:classifier"
}
"""

        repositoryInteractions {
            'org.gradle:test:1.45' {
                expectGetMetadata()
                expectGetArtifact(classifier: 'classifier')
            }
            'org.gradle:other:preview-1' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }

        expect:
        succeeds "checkDep"
        resolve.expectGraph {
            root(':', 'org.gradle:testproject:1.0') {
                module("org.gradle:test:1.45") {
                    artifact(classifier: 'classifier')
                    module("org.gradle:other:preview-1")
                }
            }
        }
    }

    def "dependency that references an artifact includes the matching artifact only plus the runtime dependencies of referenced module"() {
        given:
        repository {
            'org.gradle' {
                'other' {
                    'preview-1'()
                }
                'test' {
                    '1.45' {
                        dependsOn 'org.gradle:other:preview-1'
                        withModule {
                            artifact(type: 'aar', classifier: 'classifier')
                        }
                    }
                }
            }
        }

        and:
        buildFile << """
group = 'org.gradle'
version = '1.0'
dependencies {
    conf ("org.gradle:test:1.45") {
        artifact {
            name = 'test'
            type = 'aar'
            classifier = 'classifier'
        }
    }
}
"""
        repositoryInteractions {
            'org.gradle:test:1.45' {
                expectGetMetadata()
                expectGetArtifact(type: 'aar', classifier: 'classifier')
            }
            'org.gradle:other:preview-1' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }

        expect:
        succeeds "checkDep"
        resolve.expectGraph {
            root(':', 'org.gradle:testproject:1.0') {
                module("org.gradle:test:1.45") {
                    artifact(type: 'aar', classifier: 'classifier')
                    module("org.gradle:other:preview-1")
                }
            }
        }
    }

    def "throws readable error if an artifact name is missing"() {
        given:
        buildFile << """
dependencies {
    conf ("org.gradle:test:1.45") {
        artifact {
            classifier = 'classifier'
        }
    }
}
"""

        expect:
        fails "checkDep"
        failure.assertHasCause("Artifact name must not be null!")
    }

    @RequiredFeatures(
        // only available with Maven metadata: Gradle metadata does not support "optional"
        @RequiredFeature(feature = GradleMetadataResolveRunner.GRADLE_METADATA, value = "false")
    )
    def "does not include optional dependencies of maven module"() {
        given:
        repository {
            'org.gradle:test:1.45' {
                dependsOn group:'org.gradle', artifact:'i-do-not-exist', version:'1.45', optional: 'true'
                dependsOn group:'org.gradle', artifact:'i-do-not-exist', version:'1.45', optional: 'true', scope: 'runtime'
            }
        }
        and:

        buildFile << """
dependencies {
    conf "org.gradle:test:1.45"
}
"""

        repositoryInteractions {
            'org.gradle:test:1.45' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }

        expect:
        succeeds "checkDep"
        resolve.expectGraph {
            root(':', ':testproject:') {
                module("org.gradle:test:1.45")
            }
        }
    }

    @RequiredFeatures([
        @RequiredFeature(feature = GradleMetadataResolveRunner.GRADLE_METADATA, value = "false"),
        @RequiredFeature(feature = GradleMetadataResolveRunner.EXPERIMENTAL_RESOLVE_BEHAVIOR, value = "true")
    ])
    def "optional dependency version will not upgrade version from non-optional dependency"() {
        given:
        repository {
            'org.gradle:test:1.45' {
                dependsOn group:'org.gradle', artifact:'not-upgraded', version:'1.45', optional: 'true'
                dependsOn group:'org.gradle', artifact:'recommended', version:'1.45', optional: 'true'
            }
            'org.gradle:not-upgraded:1.44'()
            'org.gradle:recommended:1.45'()
        }
        and:

        buildFile << """
dependencies {
    conf "org.gradle:test:1.45"
    conf "org.gradle:not-upgraded:1.44"
    conf "org.gradle:recommended"
}
"""

        repositoryInteractions {
            'org.gradle:test:1.45' {
                expectResolve()
            }
            'org.gradle:not-upgraded:1.44' {
                expectResolve()
            }
            'org.gradle:recommended:1.45' {
                expectResolve()
            }
        }

        expect:
        succeeds "checkDep"
        resolve.expectGraph {
            root(':', ':testproject:') {
                module("org.gradle:not-upgraded:1.44")
                edge("org.gradle:recommended", "org.gradle:recommended:1.45")
                module("org.gradle:test:1.45") {
                    edge("org.gradle:not-upgraded:1.45", "org.gradle:not-upgraded:1.44")
                    module("org.gradle:recommended:1.45")
                }
            }
        }
    }

    @RequiredFeatures([
        @RequiredFeature(feature = GradleMetadataResolveRunner.GRADLE_METADATA, value = "false"),
        @RequiredFeature(feature = GradleMetadataResolveRunner.EXPERIMENTAL_RESOLVE_BEHAVIOR, value = "true")
    ])
    def "optional dependency version will not upgrade version from non-optional dependency"() {
        given:
        repository {
            'org.gradle:test:1.45' {
                dependsOn group:'org.gradle', artifact:'not-upgraded', version:'1.45', optional: 'true'
                dependsOn group:'org.gradle', artifact:'recommended', version:'1.45', optional: 'true'
            }
            'org.gradle:not-upgraded:1.44'()
            'org.gradle:recommended:1.45'()
        }
        and:

        buildFile << """
dependencies {
    conf "org.gradle:test:1.45"
    conf "org.gradle:not-upgraded:1.44"
    conf "org.gradle:recommended"
}
"""

        repositoryInteractions {
            'org.gradle:test:1.45' {
                expectResolve()
            }
            'org.gradle:not-upgraded:1.44' {
                expectResolve()
            }
            'org.gradle:recommended:1.45' {
                expectResolve()
            }
        }

        expect:
        succeeds "checkDep"
        resolve.expectGraph {
            root(':', ':testproject:') {
                module("org.gradle:not-upgraded:1.44")
                edge("org.gradle:recommended", "org.gradle:recommended:1.45")
                module("org.gradle:test:1.45") {
                    edge("org.gradle:not-upgraded:1.45", "org.gradle:not-upgraded:1.44")
                    module("org.gradle:recommended:1.45")
                }
            }
        }
    }

}
