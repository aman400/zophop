package dev.zophop.plugin.api;
import dev.zophop.plugin.api.NodePluginManifest;

interface INodePluginService {
    int getPluginApiVersion();
    List<NodePluginManifest> getManifests();
    String executeNode(String nodeId, String configJson, String inputContextJson);
}
