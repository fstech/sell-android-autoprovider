package com.getbase.android.autoprovider;

import com.getbase.autoindexer.DbTableModel;
import com.getbase.forger.thneed.MicroOrmModel;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

import org.chalup.thneed.ManyToManyRelationship;
import org.chalup.thneed.ModelGraph;
import org.chalup.thneed.ModelVisitor;
import org.chalup.thneed.OneToManyRelationship;
import org.chalup.thneed.OneToOneRelationship;
import org.chalup.thneed.PolymorphicRelationship;
import org.chalup.thneed.RecursiveModelRelationship;
import org.chalup.thneed.RelationshipVisitor;

import android.net.Uri;
import android.provider.BaseColumns;

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;

public class AutoUris<TModel extends DbTableModel & MicroOrmModel> implements ModelUriBuilder {
  private final ModelGraph<TModel> mModelGraph;
  private final String mAuthority;
  private final String mIdColumnName;

  private final BiMap<Class<?>, String> mClassToTableMap;
  private final Multimap<Class<?>, Class<?>> mRelationsByClasses;

  AutoUris(ModelGraph<TModel> modelGraph, String authority, String idColumnName) {
    mModelGraph = modelGraph;
    mAuthority = authority;
    mIdColumnName = idColumnName;

    final ImmutableBiMap.Builder<Class<?>, String> classToTableMappingBuilder = ImmutableBiMap.builder();
    mModelGraph.accept(new ModelVisitor<TModel>() {
      @Override
      public void visit(TModel model) {
        classToTableMappingBuilder.put(model.getModelClass(), model.getDbTable());
      }
    });
    mClassToTableMap = classToTableMappingBuilder.build();

    final HashMultimap<Class<?>, Class<?>> relationsByClass = HashMultimap.create();
    final Map<Class<?>, Class<?>> unsupportedRelations = Maps.newHashMap();
    mModelGraph.accept(new RelationshipVisitor<TModel>() {
      @Override
      public void visit(OneToManyRelationship<? extends TModel> relationship) {
        TModel manyModel = relationship.mModel;
        TModel oneModel = relationship.mReferencedModel;

        if (!relationsByClass.put(manyModel.getModelClass(), oneModel.getModelClass())) {
          unsupportedRelations.put(manyModel.getModelClass(), oneModel.getModelClass());
        }
      }

      @Override
      public void visit(OneToOneRelationship<? extends TModel> relationship) {
        TModel model = relationship.mModel;
        TModel linkedModel = relationship.mLinkedModel;

        if (!relationsByClass.put(linkedModel.getModelClass(), model.getModelClass())) {
          unsupportedRelations.put(linkedModel.getModelClass(), model.getModelClass());
        }
      }

      @Override
      public void visit(RecursiveModelRelationship<? extends TModel> relationship) {
        TModel model = relationship.mModel;

        if (!relationsByClass.put(model.getModelClass(), model.getModelClass())) {
          unsupportedRelations.put(model.getModelClass(), model.getModelClass());
        }
      }

      @Override
      public void visit(ManyToManyRelationship<? extends TModel> relationship) {
        // implementation not necessary
      }

      @Override
      public void visit(PolymorphicRelationship<? extends TModel> relationship) {
        TModel model = relationship.mModel;

        for (TModel polyModel : relationship.mPolymorphicModels.values()) {
          if (!relationsByClass.put(model.getModelClass(), polyModel.getModelClass())) {
            unsupportedRelations.put(model.getModelClass(), polyModel.getModelClass());
          }
        }
      }
    });

    for (Entry<Class<?>, Class<?>> unsupportedRelation : unsupportedRelations.entrySet()) {
      relationsByClass.remove(unsupportedRelation.getKey(), unsupportedRelation.getValue());
    }

    mRelationsByClasses = ImmutableMultimap.copyOf(relationsByClass);
  }

  public static <T extends DbTableModel & MicroOrmModel> AuthoritySelector<T> from(ModelGraph<T> modelGraph) {
    return new Builder<T>(modelGraph);
  }

  public static class Builder<TModel extends DbTableModel & MicroOrmModel> implements AuthoritySelector<TModel> {
    private final ModelGraph<TModel> mModelGraph;
    private String mIdColumnName = BaseColumns._ID;
    private String mAuthority;

    Builder(ModelGraph<TModel> modelGraph) {
      mModelGraph = modelGraph;
    }

    public Builder<TModel> defaultIdColumn(String idColumnName) {
      mIdColumnName = idColumnName;
      return this;
    }

    public AutoUris<TModel> build() {
      return new AutoUris<TModel>(mModelGraph, mAuthority, mIdColumnName);
    }

    @Override
    public Builder<TModel> forContentProvider(String authority) {
      mAuthority = authority;
      return this;
    }
  }

  public interface AuthoritySelector<TModel extends DbTableModel & MicroOrmModel> {
    Builder<TModel> forContentProvider(String authority);
  }

  @Override
  public ModelUri model(Class<?> klass) {
    Preconditions.checkNotNull(klass);
    Preconditions.checkArgument(mClassToTableMap.containsKey(klass), "Model %s is not present in supplied model graph", klass.getSimpleName());
    return new ModelUriImpl(klass);
  }

  private abstract class AutoUriImpl implements AutoUri {
    protected Map<Class<?>, EntityUri> mRelatedEntities;

    protected AutoUriImpl() {
      mRelatedEntities = Maps.newHashMap();
    }

    protected AutoUriImpl(AutoUriImpl other) {
      mRelatedEntities = Maps.newHashMap(other.mRelatedEntities);
    }

    @Override
    public Collection<EntityUri> getRelatedEntities() {
      return Lists.newCopyOnWriteArrayList(mRelatedEntities.values());
    }

    @Override
    public Optional<EntityUri> getRelatedEntity(Class<?> model) {
      Preconditions.checkNotNull(model);
      return Optional.fromNullable(mRelatedEntities.get(model));
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(mRelatedEntities);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;

      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;

      final AutoUriImpl other = (AutoUriImpl) obj;
      return Objects.equal(this.mRelatedEntities, other.mRelatedEntities);
    }
  }

  private class ModelUriImpl extends AutoUriImpl implements ModelUri {
    private final Class<?> mKlass;

    ModelUriImpl(Class<?> klass) {
      mKlass = klass;
    }

    ModelUriImpl(ModelUriImpl other) {
      super(other);
      mKlass = other.mKlass;
    }

    @Override
    public Class<?> getModel() {
      return mKlass;
    }

    @Override
    public Uri toUri() {
      return null;
    }

    @Override
    public ModelUri getModelUri() {
      return this;
    }

    @Override
    public ModelUri relatedTo(EntityUri uri) {
      Preconditions.checkNotNull(uri);
      ModelUriImpl modelUri = new ModelUriImpl(this);
      Class<?> relationModel = uri.getModelUri().getModel();
      Class<?> model = getModel();
      Preconditions.checkArgument(mRelationsByClasses.containsEntry(model, relationModel));
      EntityUri previousValue = modelUri.mRelatedEntities.put(relationModel, uri);
      Preconditions.checkArgument(previousValue == null, "Duplicate relation for model %s", relationModel.getSimpleName());
      return modelUri;
    }

    @Override
    public EntityUri id(long id) {
      return id(mIdColumnName, id);
    }

    @Override
    public EntityUri id(String column, long id) {
      EntityUriImpl entityUri = new EntityUriImpl(new ModelUriImpl(this), column, id);
      entityUri.mRelatedEntities.putAll(mRelatedEntities);
      return entityUri;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(mKlass, super.hashCode());
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      if (!super.equals(obj)) return false;

      final ModelUriImpl other = (ModelUriImpl) obj;

      return Objects.equal(this.mKlass, other.mKlass);
    }

    @Override
    public String toString() {
      return Objects
          .toStringHelper(this)
          .add("model", getModel().getSimpleName())
          .add("relatedTo", Joiner.on(", ").withKeyValueSeparator(": ").join(mRelatedEntities))
          .toString();
    }
  }

  private class EntityUriImpl extends AutoUriImpl implements EntityUri {
    private final ModelUriImpl mModelUri;
    private final String mIdColumnName;
    private final long mId;

    EntityUriImpl(ModelUriImpl modelUri, String idColumnName, long id) {
      mModelUri = modelUri;
      mIdColumnName = idColumnName;
      mId = id;
    }

    EntityUriImpl(EntityUriImpl other) {
      super(other);
      mModelUri = other.mModelUri;
      mIdColumnName = other.mIdColumnName;
      mId = other.mId;
    }

    @Override
    public long getId() {
      return mId;
    }

    @Override
    public String getIdColumn() {
      return mIdColumnName;
    }

    @Override
    public Uri toUri() {
      return null;
    }

    @Override
    public ModelUri getModelUri() {
      return mModelUri;
    }

    @Override
    public Class<?> getModel() {
      return mModelUri.getModel();
    }

    @Override
    public EntityUri relatedTo(EntityUri uri) {
      Preconditions.checkNotNull(uri);
      EntityUriImpl entityUri = new EntityUriImpl(this);
      Class<?> relationModel = uri.getModelUri().getModel();
      Class<?> model = entityUri.getModelUri().getModel();
      Preconditions.checkArgument(mRelationsByClasses.containsEntry(model, relationModel));
      EntityUri previousValue = entityUri.mRelatedEntities.put(relationModel, uri);
      Preconditions.checkArgument(previousValue == null, "Duplicate relation for model %s", relationModel.getSimpleName());
      return entityUri;
    }

    @Override
    public ModelUri model(Class<?> klass) {
      return AutoUris.this.model(klass).relatedTo(this);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(mModelUri, mIdColumnName, mId, super.hashCode());
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      if (!super.equals(obj)) return false;

      final EntityUriImpl other = (EntityUriImpl) obj;

      return Objects.equal(this.mModelUri, other.mModelUri) &&
          Objects.equal(this.mIdColumnName, other.mIdColumnName) &&
          Objects.equal(this.mId, other.mId);
    }

    @Override
    public String toString() {
      return Objects
          .toStringHelper(this)
          .add("id", mId)
          .add("idColumn", mIdColumnName)
          .add("model", getModelUri().getModel().getSimpleName())
          .add("relatedTo", Joiner.on(", ").withKeyValueSeparator(": ").join(mRelatedEntities))
          .toString();
    }
  }

  public AutoUri fromUri(Uri uri) {
    return null;
  }
}
