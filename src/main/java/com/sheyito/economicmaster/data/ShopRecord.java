package com.sheyito.economicmaster.data;

/** On-disk (Gson-friendly, all primitives) shape of a single registered shop sign. */
public class ShopRecord {
    public String dimensionId;
    public int signX;
    public int signY;
    public int signZ;
    public int chestX;
    public int chestY;
    public int chestZ;
    public String ownerUuid;
    public String ownerName;
    public String action;
    public double price;
    public String itemId;
    public int quantity;
}
