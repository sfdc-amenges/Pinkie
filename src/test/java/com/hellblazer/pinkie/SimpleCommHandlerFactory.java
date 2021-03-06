/*
 * Copyright (c) 2009, 2011 Hal Hildebrand, all rights reserved.
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
package com.hellblazer.pinkie;

import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 * 
 */
public class SimpleCommHandlerFactory implements CommunicationsHandlerFactory {
    List<SimpleCommHandler> handlers = new ArrayList<SimpleCommHandler>();

    @Override
    public SimpleCommHandler createCommunicationsHandler(SocketChannel channel) {
        SimpleCommHandler handler = new SimpleCommHandler();
        handlers.add(handler);
        return handler;
    }
}
