/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package git4idea.push;

import consulo.logging.Logger;
import consulo.util.lang.StringUtil;
import git4idea.GitUtil;
import git4idea.branch.GitBranchUtil;
import git4idea.repo.GitRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;

class GitPushSpecParser {
    private static final Logger LOG = Logger.getInstance(GitPushSpecParser.class);

    @Nullable
    static String getTargetRef(@Nonnull GitRepository repository, @Nonnull String sourceBranchName, @Nonnull List<String> specs) {
        // pushing to several pushSpecs is not supported => looking for the first one which is valid & matches the current branch
        for (String spec : specs) {
            String target = getTarget(spec, sourceBranchName);
            if (target == null) {
                LOG.info(
                    "Push spec [" + spec + "] in " + repository.getRoot() +
                        " is invalid or doesn't match source branch " + sourceBranchName
                );
            }
            else {
                return target;
            }
        }
        return null;
    }

    @Nullable
    private static String getTarget(@Nonnull String spec, @Nonnull String sourceBranch) {
        String[] parts = spec.split(":");
        if (parts.length != 2) {
            return null;
        }
        String source = parts[0].trim();
        String target = parts[1].trim();
        source = StringUtil.trimStart(source, "+");

        if (!isStarPositionValid(source, target)) {
            return null;
        }

        source = GitBranchUtil.stripRefsPrefix(source);
        sourceBranch = GitBranchUtil.stripRefsPrefix(sourceBranch);
        if (source.equals(GitUtil.HEAD) || source.equals(sourceBranch)) {
            return target;
        }

        if (source.endsWith("*")) {
            String sourceWoStar = source.substring(0, source.length() - 1);
            if (sourceBranch.startsWith(sourceWoStar)) {
                String starMeaning = sourceBranch.substring(sourceWoStar.length());
                return target.replace("*", starMeaning);
            }
        }
        return null;
    }

    private static boolean isStarPositionValid(@Nonnull String source, @Nonnull String target) {
        int sourceStar = source.indexOf('*');
        int targetStar = target.indexOf('*');
        return (sourceStar < 0 && targetStar < 0) || (sourceStar == source.length() - 1 && targetStar == target.length() - 1);
    }
}
