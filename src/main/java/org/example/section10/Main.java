package org.example.section10;

import akka.NotUsed;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Behaviors;
import akka.stream.ClosedShape;
import akka.stream.FlowShape;
import akka.stream.SinkShape;
import akka.stream.SourceShape;
import akka.stream.javadsl.*;
import org.example.section9.UtilityFunctions;
import org.example.section9.VehiclePositionMessage;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class Main {

    public static void main(String[] args) {
        Map<Integer, VehiclePositionMessage> vehicleTrackingMap = new HashMap<>();
        for (int i = 1; i <=8; i++) {
            vehicleTrackingMap.put(i, new VehiclePositionMessage(1, new Date(), 0,0));
        }

        //source - repeat some value every 10 seconds.
        Source<String, NotUsed> source = Source.repeat("go").throttle(1, Duration.ofSeconds(10));

        //flow 1 - transform into the ids of each van (ie 1..8) with mapConcat
        Flow<String, Integer, NotUsed> vehicleIds = Flow.of(String.class)
                .mapConcat(value -> List.of(1,2,3,4,5,6,7,8));

        //flow 2 - get position for each van as a VPMs with a call to the lookup method (create a new instance of
        //utility functions each time). Note that this process isn't instant so should be run in parallel.
        Flow<Integer, VehiclePositionMessage, NotUsed> vehiclePostions = Flow.of(Integer.class)
                .mapAsyncUnordered(8, vehicleId -> {
                    System.out.println("Requesting Position for vehicle " + vehicleId);
                    CompletableFuture<VehiclePositionMessage> future = new CompletableFuture<>();
                    UtilityFunctions utilityFunctions = new UtilityFunctions();
                    future.completeAsync( () -> utilityFunctions.getVehiclePosition(vehicleId));
                    return future;
                });

        //flow 3 - use previous position from the map to calculate the current speed of each vehicle. Replace the
        // position in the map with the newest position and pass the current speed downstream
        Flow<VehiclePositionMessage, VehicleSpeed, NotUsed> vehicleSpeeds = Flow.of(VehiclePositionMessage.class)
                .map ( vpm -> {
                    UtilityFunctions utilityFunctions = new UtilityFunctions();
                    VehiclePositionMessage previousVpm = vehicleTrackingMap.get(vpm.getVehicleId());
                    VehicleSpeed speed = utilityFunctions.calculateSpeed(vpm, previousVpm);
                    System.out.println("Vehicle " + vpm.getVehicleId() + " is travelling at " + speed.getSpeed());
                    vehicleTrackingMap.put(vpm.getVehicleId(), vpm);
                    return speed;
                });

        //flow 4 - filter to only keep those values with a speed > 95
        Flow<VehicleSpeed, VehicleSpeed, NotUsed> speedFilter = Flow.of(VehicleSpeed.class)
                .filter(speed -> speed.getSpeed() > 95);

        //sink - as soon as 1 value is received return it as a materialized value, and terminate the stream
        Sink<VehicleSpeed, CompletionStage<VehicleSpeed>> sink = Sink.head();

        ActorSystem actorSystem = ActorSystem.create(Behaviors.empty(), "actorSystem");

//        CompletionStage<VehicleSpeed> result = source.via(vehicleIds)
//                .async()
//                .via(vehiclePostions)
//                .async()
//                .via(vehicleSpeeds)
//                .via(speedFilter)
//                .toMat(sink, Keep.right())
//                .run(actorSystem);

        RunnableGraph<CompletionStage<VehicleSpeed>> graph = RunnableGraph.fromGraph(
                GraphDSL.create( sink, (builder, out) -> {

                    SourceShape<String> sourceShape = builder.add(Source.repeat("go")
                            .throttle(1, Duration.ofSeconds(10)));
                    //SourceShape<String> sourceShape = builder.add(source);

                    FlowShape<String,Integer> vehicleIdsShape = builder.add(vehicleIds);
                    FlowShape<Integer, VehiclePositionMessage> vehiclePositionsShape =
                            builder.add(vehiclePostions.async());
                    FlowShape<VehiclePositionMessage, VehicleSpeed> vehicleSpeedsShape =
                            builder.add(vehicleSpeeds);
                    FlowShape<VehicleSpeed, VehicleSpeed> speedFilterShape =
                            builder.add(speedFilter);

                    //DON'T NEED TO DO THIS - OUT IS OUR SINKSHAPE
                    //SinkShape<VehicleSpeed> out = builder.add(sink);

                    builder.from(sourceShape)
                    .via(vehicleIdsShape)
                    .via(vehiclePositionsShape);

                    builder.from(vehicleSpeedsShape)
                    .via(speedFilterShape)
                    .to(out);

                    builder.from(vehiclePositionsShape)
                    .via(vehicleSpeedsShape);

                    return ClosedShape.getInstance();
                })

        );

        CompletionStage<VehicleSpeed> result = graph.run(actorSystem);

        result.whenComplete( (value, throwable) -> {
            if (throwable != null) {
                System.out.println("Something went wrong " + throwable);
            }
            else {
                System.out.println("Vehicle " + value.getVehicleId() + " was going at a speed of " + value.getSpeed());
            }
            actorSystem.terminate();
        }) ;
    }

}
