/**
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.onewire.internal;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.openhab.binding.onewire.OneWireBindingProvider;
import org.openhab.binding.onewire.internal.connection.OneWireConnection;
import org.openhab.binding.onewire.internal.control.AbstractOneWireControlBindingConfig;
import org.openhab.binding.onewire.internal.deviceproperties.AbstractOneWireDevicePropertyBindingConfig;
import org.openhab.binding.onewire.internal.deviceproperties.AbstractOneWireDevicePropertyWritableBindingConfig;
import org.openhab.binding.onewire.internal.listener.InterfaceOneWireDevicePropertyWantsUpdateListener;
import org.openhab.binding.onewire.internal.listener.OneWireDevicePropertyWantsUpdateEvent;
import org.openhab.binding.onewire.internal.scheduler.OneWireUpdateScheduler;
import org.openhab.core.binding.AbstractBinding;
import org.openhab.core.binding.BindingConfig;
import org.openhab.core.binding.BindingProvider;
import org.openhab.core.items.Item;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.Type;
import org.openhab.core.types.UnDefType;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The 1-wire items / device properties are scheduled and refreshed via
 * OneWireUpdateScheduler for this binding
 *
 * @author Thomas.Eichstaedt-Engelen, Dennis Riegelbauer
 * @since 0.6.0
 */
public class OneWireBinding extends AbstractBinding<OneWireBindingProvider>
        implements ManagedService, InterfaceOneWireDevicePropertyWantsUpdateListener {

    private static final Logger logger = LoggerFactory.getLogger(OneWireBinding.class);

    /**
     * Scheduler for items
     */
    private OneWireUpdateScheduler ivOneWireReaderScheduler;

    /**
     * Use the Cache to post only changed values for items to the eventPublisher
     */
    private boolean ivPostOnlyChangedValues = true;

    /**
     * Cache of item values
     */
    private Hashtable<String, State> ivCacheItemStates = new Hashtable<String, State>();

    public OneWireBinding() {
        super();
        ivOneWireReaderScheduler = new OneWireUpdateScheduler(this);
    }

    @Override
    public void activate() {
        super.activate();
        ivOneWireReaderScheduler.start();
    }

    @Override
    public void deactivate() {
        super.deactivate();
        ivOneWireReaderScheduler.stop();
    }

    protected void addBindingProvider(OneWireBindingProvider bindingProvider) {
        super.addBindingProvider(bindingProvider);
    }

    protected void removeBindingProvider(OneWireBindingProvider bindingProvider) {
        super.removeBindingProvider(bindingProvider);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.osgi.service.cm.ManagedService#updated(java.util.Dictionary)
     */
    @Override
    public void updated(Dictionary<String, ?> pvConfig) throws ConfigurationException {
        if (pvConfig != null) {
            // Basic config
            String lvPostOnlyChangedValues = (String) pvConfig.get("post_only_changed_values");
            if (StringUtils.isNotBlank(lvPostOnlyChangedValues)) {
                ivPostOnlyChangedValues = Boolean.getBoolean(lvPostOnlyChangedValues);
            }

            // Connection config
            OneWireConnection.updated(pvConfig);
        }

        for (OneWireBindingProvider lvProvider : providers) {
            scheduleAllBindings(lvProvider);
        }

    }

    @Override
    protected void internalReceiveCommand(String pvItemName, Command pvCommand) {
        logger.debug("received command " + pvCommand.toString() + " for item " + pvItemName);

        OneWireBindingConfig lvBindigConfig = getBindingConfig(pvItemName);

        if (lvBindigConfig instanceof AbstractOneWireDevicePropertyWritableBindingConfig) {
            AbstractOneWireDevicePropertyWritableBindingConfig lvWritableBindingConfig = (AbstractOneWireDevicePropertyWritableBindingConfig) lvBindigConfig;

            String lvStringValue = lvWritableBindingConfig.convertTypeToString(pvCommand);

            OneWireConnection.writeToOneWire(lvWritableBindingConfig.getDevicePropertyPath(), lvStringValue);
        } else if (lvBindigConfig instanceof AbstractOneWireControlBindingConfig) {
            AbstractOneWireControlBindingConfig lvControlBindingConfig = (AbstractOneWireControlBindingConfig) lvBindigConfig;
            lvControlBindingConfig.executeControl(this, pvCommand);
        } else {
            logger.debug("received command " + pvCommand.toString() + " for item " + pvItemName
                    + " which is not writable or executable");
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.openhab.core.binding.AbstractBinding#allBindingsChanged(org.openhab
     * .core.binding.BindingProvider)
     */
    @Override
    public void allBindingsChanged(BindingProvider pvProvider) {
        scheduleAllBindings(pvProvider);
    }

    /**
     * schedule All Bindings to get updated
     *
     * @param pvProvider
     */
    private void scheduleAllBindings(BindingProvider pvProvider) {
        if (OneWireConnection.isConnectionEstablished()) {
            logger.debug("scheduleAllBindings");

            if (pvProvider instanceof OneWireBindingProvider) {
                OneWireBindingProvider lvBindingProvider = (OneWireBindingProvider) pvProvider;
                ivOneWireReaderScheduler.clear();
                ivCacheItemStates.clear();

                Map<String, BindingConfig> lvBindigConfigs = lvBindingProvider.getBindingConfigs();
                for (String lvItemName : lvBindigConfigs.keySet()) {
                    logger.debug("scheduleAllBindings, now item {}.", lvItemName);
                    OneWireBindingConfig lvOneWireBindingConfig = (OneWireBindingConfig) lvBindigConfigs
                            .get(lvItemName);
                    if (lvOneWireBindingConfig instanceof AbstractOneWireDevicePropertyBindingConfig) {
                        logger.debug("Initializing read of item {}.", lvItemName);

                        AbstractOneWireDevicePropertyBindingConfig lvDevicePropertyBindingConfig = (AbstractOneWireDevicePropertyBindingConfig) lvOneWireBindingConfig;

                        if (lvDevicePropertyBindingConfig != null) {
                            int lvAutoRefreshTimeInSecs = lvDevicePropertyBindingConfig.getAutoRefreshInSecs();
                            if (lvAutoRefreshTimeInSecs > -1) {
                                ivOneWireReaderScheduler.updateOnce(lvItemName);
                            }

                            if (lvAutoRefreshTimeInSecs > 0) {
                                if (!ivOneWireReaderScheduler.scheduleUpdate(lvItemName, lvAutoRefreshTimeInSecs)) {
                                    logger.warn("Couldn't add to OneWireUpdate scheduler",
                                            lvDevicePropertyBindingConfig);
                                }
                            }
                        }
                    } else {
                        logger.debug("Didn't schedule item {} because it is not a DevicePropertyBinding.", lvItemName);
                    }
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.openhab.core.binding.AbstractBinding#bindingChanged(org.openhab.core
     * .binding.BindingProvider, java.lang.String)
     */
    @Override
    public void bindingChanged(BindingProvider pvProvider, String pvItemName) {
        logger.debug("bindingChanged() for item {} msg received.", pvItemName);

        if (pvProvider instanceof OneWireBindingProvider) {
            ivCacheItemStates.remove(pvItemName);

            OneWireBindingProvider lvBindingProvider = (OneWireBindingProvider) pvProvider;

            OneWireBindingConfig lvBindingConfig = lvBindingProvider.getBindingConfig(pvItemName);

            // Only for AbstractOneWireDevicePropertyBindingConfig, not for
            // AbstractOneWireControlBindingConfigs
            if (lvBindingConfig != null && lvBindingConfig instanceof AbstractOneWireDevicePropertyBindingConfig) {
                AbstractOneWireDevicePropertyBindingConfig lvDeviceBindingConfig = (AbstractOneWireDevicePropertyBindingConfig) lvBindingConfig;

                logger.debug("Initializing read of item {}.", pvItemName);
                int lvAutoRefreshTimeInSecs = lvDeviceBindingConfig.getAutoRefreshInSecs();

                if (lvAutoRefreshTimeInSecs > -1) {
                    ivOneWireReaderScheduler.updateOnce(pvItemName);
                }

                if (lvAutoRefreshTimeInSecs > 0) {
                    if (!ivOneWireReaderScheduler.scheduleUpdate(pvItemName, lvAutoRefreshTimeInSecs)) {
                        logger.warn("Couldn't add to OneWireUpdate scheduler", lvDeviceBindingConfig);
                    }
                } else {
                    logger.debug("Didn't add to OneWireUpdate scheduler, because refresh is <= 0: "
                            + lvDeviceBindingConfig.toString());
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.openhab.binding.onewire.internal.listener.
     * InterfaceOneWireDevicePropertyWantsUpdateListener#
     * devicePropertyWantsUpdate(org.openhab.binding.onewire.internal.listener.
     * OneWireDevicePropertyWantsUpdateEvent)
     */
    @Override
    public void devicePropertyWantsUpdate(OneWireDevicePropertyWantsUpdateEvent pvWantsUpdateEvent) {
        String lvItemName = pvWantsUpdateEvent.getItemName();

        logger.debug("Item " + lvItemName + " wants update!");

        updateItemFromOneWire(lvItemName);
    }

    /**
     *
     * @param pvItemName
     * @return the corresponding AbstractOneWireDevicePropertyBindingConfig to
     *         the given <code>pvItemName</code>
     */
    private OneWireBindingConfig getBindingConfig(String pvItemName) {
        for (OneWireBindingProvider lvProvider : providers) {
            return lvProvider.getBindingConfig(pvItemName);
        }
        return null;
    }

    /**
     *
     * @param pvItemName
     * @return the corresponding Item to the given <code>pvItemName</code>
     */
    private Item getItem(String pvItemName) {
        for (OneWireBindingProvider lvProvider : providers) {
            return lvProvider.getItem(pvItemName);
        }
        return null;
    }

    /**
     * Update an item with value from 1-wire device property
     *
     * @param pvItemName
     */
    public void updateItemFromOneWire(String pvItemName) {
        if (OneWireConnection.getConnection() != null) {

            AbstractOneWireDevicePropertyBindingConfig pvBindingConfig = (AbstractOneWireDevicePropertyBindingConfig) getBindingConfig(
                    pvItemName);

            if (pvBindingConfig == null) {
                logger.error("no bindingConfig found for itemName=" + pvItemName
                        + " cannot update! It will be removed from scheduler");
                ivOneWireReaderScheduler.removeItem(pvItemName);
                return;
            }

            String lvReadValue = OneWireConnection.readFromOneWire(pvBindingConfig);

            Item lvItem = getItem(pvItemName);
            if (lvReadValue != null) {
                Type lvNewType = pvBindingConfig.convertReadValueToType(lvReadValue);
                if (lvItem != null) {
                    postUpdate(lvItem, lvNewType);
                } else {
                    logger.error("There is no Item for ItemName=" + pvItemName);
                }
            } else {
                String lvLogText = "Set Item for itemName=" + pvItemName
                        + " to Undefined, because the readvalue is null";
                if (pvBindingConfig.isIgnoreReadErrors()) {
                    logger.debug(lvLogText);
                } else {
                    logger.error(lvLogText);
                }

                postUpdate(lvItem, UnDefType.UNDEF);
            }
        }
    }

    private void postUpdate(Item pvItem, Type pvNewType) {
        synchronized (pvItem) {
            State lvNewState = (State) pvNewType;
            State lvCachedState = ivCacheItemStates.get(pvItem.getName());
            if (!ivPostOnlyChangedValues || !lvNewState.equals(lvCachedState)) {
                ivCacheItemStates.remove(pvItem.getName());
                ivCacheItemStates.put(pvItem.getName(), lvNewState);
                eventPublisher.postUpdate(pvItem.getName(), lvNewState);
            } else {
                logger.debug("didn't post update to eventPublisher, because state did not change for item "
                        + pvItem.getName());
            }
        }
    }

    /**
     * Clears the Cache for ItemStates
     */
    public void clearCacheItemState() {
        this.ivCacheItemStates.clear();
    }

    /**
     * Clears the Cache for given Item
     */
    public void clearCacheItemState(String pvItenName) {
        this.ivCacheItemStates.remove(pvItenName);
    }
}
