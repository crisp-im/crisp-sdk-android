package im.crisp.sdk.models;

/**
 * Created by baptistejamin on 03/05/2017.
 */

public class BucketGenerated {
    public String from;
    public Long id;
    public String identifier;
    public BucketUrlGenerated url;

    public class BucketUrlGenerated {
        public String resource;
        public String signed;
    }
}