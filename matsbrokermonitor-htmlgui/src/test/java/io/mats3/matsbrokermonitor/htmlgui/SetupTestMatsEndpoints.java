package io.mats3.matsbrokermonitor.htmlgui;

import java.util.Objects;

import io.mats3.MatsEndpoint.ProcessContext;
import org.junit.Assert;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsFactory;

/**
 * @author Endre Stølsvik 2021-12-31 01:50 - http://stolsvik.com/, endre@stolsvik.com
 */
public class SetupTestMatsEndpoints {

    public static int BASE_CONCURRENCY = 5;

    static void setupMatsTestEndpoints(String servicePrefix, MatsFactory matsFactory) {
        setupMainMultiStagedService(servicePrefix, matsFactory);
        setupMidMultiStagedService(servicePrefix, matsFactory);
        setupLeafService(servicePrefix, matsFactory);

        setupTerminator(servicePrefix, matsFactory);
        setupSubscriptionTerminator(servicePrefix, matsFactory);
    }

    static final String SERVICE_MAIN = ".mainService";
    static final String SERVICE_MID = ".private.midMethod";
    static final String SERVICE_LEAF = ".private.leafMethod";
    static final String TERMINATOR = ".terminator";
    static final String SUBSCRIPTION_TERMINATOR = ".subscriptionTerminator";

    // If present as TraceProperty with Boolean.TRUE, the randomThrow(context) won't throw!
    static final String DONT_THROW = "DONT_THROW";

    public static void setupLeafService(String servicePrefix, MatsFactory matsFactory) {
        MatsEndpoint<DataTO, Void> single = matsFactory.single(servicePrefix + SERVICE_LEAF, DataTO.class, DataTO.class,
                (context, dto) -> {

                    randomThrow(context);

                    // Use the 'multiplier' in the request to formulate the reply.. I.e. multiply the number..!
                    return new DataTO(dto.number * dto.multiplier, dto.string + ":FromLeafService");
                });
        single.getEndpointConfig().setConcurrency(BASE_CONCURRENCY * 6);
    }

    public static void setupMidMultiStagedService(String servicePrefix, MatsFactory matsFactory) {
        MatsEndpoint<DataTO, StateTO> ep = matsFactory.staged(servicePrefix + SERVICE_MID, DataTO.class,
                StateTO.class);
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(0, 0), sto);
            // Store the multiplier in state, so that we can use it when replying in the next (last) stage.
            sto.number1 = dto.multiplier;
            // Add an important number to state..!
            sto.number2 = Math.PI;
            context.request(servicePrefix + SERVICE_LEAF, new DataTO(dto.number, dto.string + ":LeafCall", 2));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // Only assert number2, as number1 is differing between calls (it is the multiplier for MidService).
            Assert.assertEquals(Math.PI, sto.number2, 0d);
            // Change the important number in state..!
            sto.number2 = Math.E;

            randomThrow(context);

            context.next(new DataTO(dto.number, dto.string + ":NextCall"));
        });
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            // Only assert number2, as number1 is differing between calls (it is the multiplier for MidService).
            Assert.assertEquals(Math.E, sto.number2, 0d);
            // Use the 'multiplier' in the request to formulate the reply.. I.e. multiply the number..!
            return new DataTO(dto.number * sto.number1, dto.string + ":FromMidService");
        });

        ep.getEndpointConfig().setConcurrency(BASE_CONCURRENCY * 4);
    }

    public static void setupMainMultiStagedService(String servicePrefix, MatsFactory matsFactory) {
        MatsEndpoint<DataTO, StateTO> ep = matsFactory.staged(servicePrefix + SERVICE_MAIN, DataTO.class,
                StateTO.class);
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // We don't assert initial state, as that might be set or not, based on whether initialState is sent along.
            sto.number1 = Integer.MAX_VALUE;
            sto.number2 = Math.E;
            context.request(servicePrefix + SERVICE_MID, new DataTO(dto.number, dto.string + ":MidCall1", 3));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE, Math.E), sto);
            sto.number1 = 1;
            sto.number2 = 2;

            randomThrow(context);

            context.next(dto);
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(1, 2), sto);
            sto.number1 = Integer.MIN_VALUE;
            sto.number2 = Math.E * 2;
            context.request(servicePrefix + SERVICE_MID, new DataTO(dto.number, dto.string + ":MidCall2", 7));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE, Math.E * 2), sto);
            sto.number1 = Integer.MIN_VALUE / 2;
            sto.number2 = Math.E / 2;
            context.request(servicePrefix + SERVICE_LEAF, new DataTO(dto.number, dto.string + ":LeafCall1", 4));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE / 2, Math.E / 2), sto);
            sto.number1 = Integer.MIN_VALUE / 4;
            sto.number2 = Math.E / 4;
            context.request(servicePrefix + SERVICE_LEAF, new DataTO(dto.number, dto.string + ":LeafCall2", 6));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE / 4, Math.E / 4), sto);
            sto.number1 = Integer.MAX_VALUE / 2;
            sto.number2 = Math.PI / 2;
            context.request(servicePrefix + SERVICE_MID, new DataTO(dto.number, dto.string + ":MidCall3", 8));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE / 2, Math.PI / 2), sto);
            sto.number1 = Integer.MAX_VALUE / 4;
            sto.number2 = Math.PI / 4;
            context.request(servicePrefix + SERVICE_MID, new DataTO(dto.number, dto.string + ":MidCall4", 9));
        });
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE / 4, Math.PI / 4), sto);

            randomThrow(context);

            return new DataTO(dto.number * 5, dto.string + ":FromMasterService");
        });
    }

    public static void setupTerminator(String servicePrefix, MatsFactory matsFactory) {
        matsFactory.terminator(servicePrefix + TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    // RANDOM THROW!
                    if (Math.random() < 0.01) {
                        throw new MatsRefuseMessageException("Random from Terminator DLQ!");
                    }
                });
    }

    public static void setupSubscriptionTerminator(String servicePrefix, MatsFactory matsFactory) {
        matsFactory.subscriptionTerminator(servicePrefix + SUBSCRIPTION_TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                });
    }

    private static void randomThrow(ProcessContext<?> context) throws MatsRefuseMessageException {
        if (context.getTraceProperty("DONT_THROW", Boolean.class) == Boolean.TRUE) {
            return;
        }
        if (Math.random() < 0.01) {
            throw new MatsRefuseMessageException("Random DLQ!");
        }
    }

    public static class DataTO {
        public double number;
        public String string;

        // This is used for the "Test_ComplexLargeMultiStage" to tell the service what it should multiply 'number'
        // with..!
        public int multiplier;

        public DataTO() {
            // For Jackson JSON-lib which needs default constructor.
        }

        public DataTO(double number, String string) {
            this.number = number;
            this.string = string;
        }

        public DataTO(double number, String string, int multiplier) {
            this.number = number;
            this.string = string;
            this.multiplier = multiplier;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            // NOTICE: Not Class-equals, but "instanceof", since we accept the "SubDataTO" too.
            if (!(o instanceof DataTO)) return false;
            DataTO dataTO = (DataTO) o;
            return Double.compare(dataTO.number, number) == 0 &&
                    multiplier == dataTO.multiplier &&
                    Objects.equals(string, dataTO.string);
        }

        @Override
        public int hashCode() {
            return Objects.hash(number, string, multiplier);
        }

        @Override
        public String toString() {
            return "DataTO [number=" + number
                    + ", string=" + string
                    + (multiplier != 0 ? ", multiplier=" + multiplier : "")
                    + "]";
        }
    }

    public static class StateTO {
        public int number1;
        public double number2;

        public StateTO() {
            // For Jackson JSON-lib which needs default constructor.
        }

        public StateTO(int number1, double number2) {
            this.number1 = number1;
            this.number2 = number2;
        }

        @Override
        public int hashCode() {
            return (number1 * 3539) + (int) Double.doubleToLongBits(number2 * 99713.80309);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof StateTO)) {
                throw new AssertionError(StateTO.class.getSimpleName() + " was attempted equalled to [" + obj + "].");
            }
            StateTO other = (StateTO) obj;
            return (this.number1 == other.number1) && (this.number2 == other.number2);
        }

        @Override
        public String toString() {
            return "StateTO [number1=" + number1 + ", number2=" + number2 + "]";
        }
    }
}
