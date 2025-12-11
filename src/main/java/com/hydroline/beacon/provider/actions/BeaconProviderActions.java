package com.hydroline.beacon.provider.actions;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hydroline.beacon.provider.actions.dto.BeaconPingResponse;
import com.hydroline.beacon.provider.actions.dto.MtrDepotListResponse;
import com.hydroline.beacon.provider.actions.dto.MtrFareAreaListResponse;
import com.hydroline.beacon.provider.actions.dto.MtrDepotTrainsResponse;
import com.hydroline.beacon.provider.actions.dto.MtrNetworkOverviewResponse;
import com.hydroline.beacon.provider.actions.dto.MtrNodePageResponse;
import com.hydroline.beacon.provider.actions.dto.MtrRouteDetailResponse;
import com.hydroline.beacon.provider.actions.dto.MtrRouteTrainsResponse;
import com.hydroline.beacon.provider.actions.dto.MtrStationListResponse;
import com.hydroline.beacon.provider.actions.dto.MtrStationTimetableResponse;
import com.hydroline.beacon.provider.channel.BeaconActionCall;

/**
 * 预置 Beacon Provider 模块当前支持的 action builder，方便 Bukkit 端调用。
 */
public final class BeaconProviderActions {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private BeaconProviderActions() {
    }

    public static BeaconActionCall<BeaconPingResponse> ping(String echo) {
        ObjectNode payload = objectNode();
        if (!isNullOrEmpty(echo)) {
            payload.put("echo", echo);
        }
        return BeaconActionCall.of("beacon:ping", payload, BeaconPingResponse.class);
    }

    public static BeaconActionCall<MtrNetworkOverviewResponse> listNetworkOverview(String dimension) {
        ObjectNode payload = objectNode();
        if (!isNullOrEmpty(dimension)) {
            payload.put("dimension", dimension);
        }
        return BeaconActionCall.of("mtr:list_network_overview", payload, MtrNetworkOverviewResponse.class);
    }

    public static BeaconActionCall<MtrRouteDetailResponse> getRouteDetail(String dimension, long routeId) {
        requireDimension(dimension);
        ObjectNode payload = objectNode();
        payload.put("dimension", dimension);
        payload.put("routeId", routeId);
        return BeaconActionCall.of("mtr:get_route_detail", payload, MtrRouteDetailResponse.class);
    }

    public static BeaconActionCall<MtrDepotListResponse> listDepots(String dimension) {
        ObjectNode payload = objectNode();
        if (!isNullOrEmpty(dimension)) {
            payload.put("dimension", dimension);
        }
        return BeaconActionCall.of("mtr:list_depots", payload, MtrDepotListResponse.class);
    }

    public static BeaconActionCall<MtrFareAreaListResponse> listFareAreas(String dimension) {
        requireDimension(dimension);
        ObjectNode payload = objectNode();
        payload.put("dimension", dimension);
        return BeaconActionCall.of("mtr:list_fare_areas", payload, MtrFareAreaListResponse.class);
    }

    public static BeaconActionCall<MtrNodePageResponse> listNodesPaginated(String dimension, String cursor, Integer limit) {
        requireDimension(dimension);
        ObjectNode payload = objectNode();
        payload.put("dimension", dimension);
        if (!isNullOrEmpty(cursor)) {
            payload.put("cursor", cursor);
        }
        if (limit != null && limit > 0) {
            payload.put("limit", limit);
        }
        return BeaconActionCall.of("mtr:list_nodes_paginated", payload, MtrNodePageResponse.class);
    }

    public static BeaconActionCall<MtrStationTimetableResponse> getStationTimetable(String dimension,
                                                                                   long stationId,
                                                                                   Long platformId) {
        requireDimension(dimension);
        ObjectNode payload = objectNode();
        payload.put("dimension", dimension);
        payload.put("stationId", stationId);
        if (platformId != null) {
            payload.put("platformId", platformId);
        }
        return BeaconActionCall.of("mtr:get_station_timetable", payload, MtrStationTimetableResponse.class);
    }

    public static BeaconActionCall<MtrStationListResponse> listStations(String dimension) {
        requireDimension(dimension);
        ObjectNode payload = objectNode();
        payload.put("dimension", dimension);
        return BeaconActionCall.of("mtr:list_stations", payload, MtrStationListResponse.class);
    }

    public static BeaconActionCall<MtrRouteTrainsResponse> getRouteTrains(String dimension, long routeId) {
        requireDimension(dimension);
        ObjectNode payload = objectNode();
        payload.put("dimension", dimension);
        payload.put("routeId", routeId);
        return BeaconActionCall.of("mtr:get_route_trains", payload, MtrRouteTrainsResponse.class);
    }

    public static BeaconActionCall<MtrDepotTrainsResponse> getDepotTrains(String dimension, long depotId) {
        requireDimension(dimension);
        ObjectNode payload = objectNode();
        payload.put("dimension", dimension);
        payload.put("depotId", depotId);
        return BeaconActionCall.of("mtr:get_depot_trains", payload, MtrDepotTrainsResponse.class);
    }

    public static BeaconActionCall<ObjectNode> getRailwaySnapshot(String dimension) {
        ObjectNode payload = objectNode();
        if (!isNullOrEmpty(dimension)) {
            payload.put("dimension", dimension);
        }
        return BeaconActionCall.of("mtr:get_railway_snapshot", payload, ObjectNode.class);
    }

    private static ObjectNode objectNode() {
        return JSON.objectNode();
    }

    private static boolean isNullOrEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void requireDimension(String dimension) {
        if (isNullOrEmpty(dimension)) {
            throw new IllegalArgumentException("dimension is required");
        }
    }
}
