/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.eightsleep.internal;

import static org.openhab.binding.eightsleep.internal.EightSleepBindingConstants.*;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eightsleep.internal.handler.AccountHandler;
import org.openhab.binding.eightsleep.internal.handler.BedSideHandler;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link EightSleepHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Joe Wang - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.eightsleep", service = ThingHandlerFactory.class)
public class EightSleepHandlerFactory extends BaseThingHandlerFactory {

    private final TimeZoneProvider timeZoneProvider;

    @Activate
    public EightSleepHandlerFactory(final @Reference TimeZoneProvider timeZoneProvider) {
        this.timeZoneProvider = timeZoneProvider;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (THING_TYPE_UID_ACCOUNT.equals(thingTypeUID)) {
            return new AccountHandler((Bridge) thing);
        }
        if (THING_TYPE_UID_BED_SIDE.equals(thingTypeUID)) {
            return new BedSideHandler(thing, timeZoneProvider);
        }
        return null;
    }
}
