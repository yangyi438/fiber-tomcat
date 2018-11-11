
package org.apache.tomcat.util.net;

import co.paralleluniverse.common.monitoring.MonitorType;
import co.paralleluniverse.fibers.FiberForkJoinScheduler;
import co.paralleluniverse.fibers.FiberScheduler;

//copy from
public class ContainerFiberScheduler {

    public static FiberScheduler createFiberScheduler(String name, int par) {
        return new FiberForkJoinScheduler(name, par, MonitorType.JMX, false);
    }
}
