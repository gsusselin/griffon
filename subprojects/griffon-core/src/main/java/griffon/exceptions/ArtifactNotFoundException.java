/*
 * Copyright 2008-2015 the original author or authors.
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
package griffon.exceptions;

import griffon.core.artifact.GriffonArtifact;
import griffon.core.artifact.GriffonClass;

import javax.annotation.Nonnull;

/**
 * @author Andres Almiray
 * @since 2.0.0
 */
public class ArtifactNotFoundException extends GriffonException {
    private static final long serialVersionUID = -7881105306242340254L;

    public ArtifactNotFoundException(@Nonnull Throwable cause) {
        super("Could not find artifact", checkNonNull(cause, "cause"));
    }

    public ArtifactNotFoundException(@Nonnull Class<?> clazz) {
        super(format(clazz));
    }

    public ArtifactNotFoundException(@Nonnull GriffonClass griffonClass, @Nonnull Throwable cause) {
        super(format(griffonClass), checkNonNull(cause, "cause"));
    }

    public ArtifactNotFoundException(@Nonnull Class<? extends GriffonArtifact> clazz, @Nonnull String name) {
        super(format(clazz, name));
    }

    private static String format(GriffonClass griffonClass) {
        return "Could not find artifact for " + checkNonNull(griffonClass, "griffonClass").getPropertyName();
    }

    private static String format(Class<?> clazz) {
        return "Could not find artifact for " + checkNonNull(clazz, "clazz").getName();
    }

    private static String format(Class<? extends GriffonArtifact> clazz, String name) {
        return "Could not find artifact of type " + checkNonNull(clazz, "clazz").getName() + " named " + checkNonBlank(name, "name");
    }
}
