package com.chestlogger.container;

/**
 * Duck-typing interface injected into AbstractContainerMenu to store container coordinates and dimension.
 */
public interface MenuContainerAccessor {
    ContainerContext chestlogger$getContainerContext();
    void chestlogger$setContainerContext(ContainerContext context);
}
