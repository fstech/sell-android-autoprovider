package com.getbase.android.autoprovider;

public interface AutoUriRelationBuilder<T> {
  T relatedTo(EntityUri uri);
  T relatedTo(String relationColumn, EntityUri uri);
}
