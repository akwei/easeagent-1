/*
 * Copyright (c) 2017, MegaEase
 * All rights reserved.
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

 package com.megaease.easeagent.metrics;

import com.megaease.easeagent.common.AnyCall;
import com.megaease.easeagent.common.CallTrace;
import com.megaease.easeagent.core.AdviceTo;
import com.megaease.easeagent.core.Configurable;
import com.megaease.easeagent.core.Definition;
import com.megaease.easeagent.core.Injection;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Collections;
import java.util.List;

@Configurable(bind = "metrics.caller")
@Injection.Provider(Provider.class)
public abstract class CaptureCaller extends AnyCall {
    @Override
    @AdviceTo(Method.class)
    protected abstract Definition.Transformer method(ElementMatcher<? super MethodDescription> matcher);

    @Override
    @Configurable.Item
    protected List<String> include_class_prefix_list() {
        return Collections.emptyList();
    }

    @Override
    @Configurable.Item
    protected List<String> exclude_class_prefix_list() {
        return Collections.emptyList();
    }

    static class Method {
        private final CallTrace trace;

        @Injection.Autowire
        Method(CallTrace trace) {this.trace = trace;}

        @Advice.OnMethodEnter
        boolean enter(@Advice.Origin final Class<?> aClass, @Advice.Origin("#m") final String method) {
            return Context.fork(trace, aClass, method);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        void exit(@Advice.Enter boolean forked) {
            if (forked) Context.join(trace);
        }
    }
}
