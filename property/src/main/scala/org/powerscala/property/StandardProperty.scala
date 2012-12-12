package org.powerscala.property

import backing.{VariableBacking, Backing}
import event.{PropertyChangingEvent, PropertyChangeEvent}
import org.powerscala.event.{ChangeEvent, Listenable}

import org.powerscala.reflect._
import org.powerscala.bus.{RoutingResults, RoutingResponse, Routing}

/**
 * StandardProperty is the default implementation of mutable properties with change listening and
 * interception.
 *
 * @author Matt Hicks <mhicks@powerscala.org>
 */
class StandardProperty[T](_name: String, val default: T, backing: Backing[T] = new VariableBacking[T])
                         (implicit override val parent: PropertyParent, val manifest: Manifest[T])
                                    extends MutableProperty[T]
                                    with CaseClassProperty[T]
                                    with Listenable
//                                    with Bindable[T]
                                    with Default[T] {
  def this(_name: String = null)(implicit parent: PropertyParent = null, manifest: Manifest[T]) = {
    this(_name, manifest.erasure.defaultForType[T])(parent, manifest)
  }

  val name = () => _name
  @volatile private var _modified = false
  backing.setValue(default)

  /**
   * Modified represents whether the value has been updated since the default value was assigned or revert was called.
   */
  def modified = _modified

  /**
   * Reverts back to the default value and resets the status of "modified".
   */
  def revert() = {
    apply(default)
    _modified = false
  }

  def apply(v: T) = {
    val oldValue = backing.getValue
    fire(PropertyChangingEvent(this, oldValue, v)) match {
      case Routing.Stop => // We're finished
      case routing: RoutingResponse => valueChanged(oldValue, routing.response.asInstanceOf[T])
      case routing: RoutingResults => throw new UnsupportedOperationException("RoutingResults not supported for PropertyChangingEvents")
      case _ => valueChanged(oldValue, v)
    }
  }

  private def valueChanged(oldValue: T, newValue: T) = {
    _modified = true
    if (oldValue != newValue) {
      backing.setValue(newValue)
      fire(PropertyChangeEvent(this, oldValue, newValue))
    }
  }

  def apply() = backing.getValue

  def onChange(f: => Any) = listeners.synchronous {
    case evt: ChangeEvent => f
  }

  def bind(property: StandardProperty[T], twoWay: Boolean = false) = {
    property.listeners.synchronous {
      case evt: PropertyChangeEvent => this := property()
    }
    if (twoWay) {
      listeners.synchronous {
        case evt: PropertyChangeEvent => property := this()
      }
    }
  }

  def bindTo[O](property: StandardProperty[O])(implicit conversion: O => T) = {
    property.listeners.synchronous {
      case evt: PropertyChangeEvent => this := conversion(property())
    }
  }

  def readOnly: Property[T] = this

  /**
   * Convenience method for notifying that a change has occured.
   */
  def fireChanged() = fire(new PropertyChangeEvent(this, value, value))

  def and(property: StandardProperty[T]) = StandardPropertyGroup[T](List(property, this))

  override def toString() = "Property[%s](%s)".format(name, value)
}

case class StandardPropertyGroup[T](properties: List[StandardProperty[T]]) {
  // TODO: evaluate other methods and means

  def :=(value: T) = properties.foreach {
    case p => p := value
  }

  def and(property: StandardProperty[T]) = copy[T](property :: properties)
}